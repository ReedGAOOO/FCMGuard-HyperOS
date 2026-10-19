package com.reed.fcmguard;

import static org.junit.Assert.*;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AurogonProtectionEngineTest {
    private static final String CIVET = "broadcastctrl:true#com.fsc.civetphone/com.google.android.c2dm.intent.RECEIVE";
    private static final String LINE = "jp.naver.line.android";
    private static final String ACTION = AurogonConfig.FCM_RECEIVE;

    @Test public void nullAndEmptyValuesCanBeInitialized() {
        String expected = "broadcastctrl:true#com.reed.fcmguard/__FCMGUARD_AUROGON_AUTOSTART_V1__#"+LINE+"/"+ACTION;
        assertEquals(expected, AurogonConfig.mergeAutostartRules(null, null, Collections.singletonList(LINE)));
        assertEquals(expected, AurogonConfig.mergeAutostartRules("", null, Collections.singletonList(LINE)));
    }
    @Test public void preservesUnmanagedSectionsAndOrdering() {
        String raw = CIVET + ";other:true#pkg.one/action#pkg.two/action";
        String out = AurogonConfig.mergeAutostartRules(raw, null, Collections.singletonList(LINE));
        assertTrue(out.startsWith(raw + ";"));
    }
    @Test public void existingReceiveOrAllRuleIsNotDuplicated() {
        String raw = "broadcastctrl:true#"+LINE+"/"+ACTION;
        assertEquals(raw + ";broadcastctrl:true#com.reed.fcmguard/__FCMGUARD_AUROGON_AUTOSTART_V1__",
                AurogonConfig.mergeAutostartRules(raw, null, Collections.singletonList(LINE)));
        String all = "broadcastctrl:true#"+LINE+"/ALL";
        assertFalse(AurogonConfig.mergeAutostartRules(all, null, Collections.singletonList(LINE)).contains(LINE+"/"+ACTION));
    }
    @Test public void repeatedPackageAndExistingAppsAreDeduplicated() {
        String out = AurogonConfig.mergeAutostartRules(CIVET, null, Arrays.asList(LINE, LINE, "com.example.app", "com.example.app"));
        assertEquals(1, count(out, LINE+"/"+ACTION));
        assertEquals(1, count(out, "com.example.app/"+ACTION));
        assertTrue(out.startsWith(CIVET+";"));
    }
    @Test public void staleOwnedPackageRemovedButOtherActionsRemain() {
        String raw = CIVET + ";broadcastctrl:true#com.reed.fcmguard/__FCMGUARD_AUROGON_AUTOSTART_V1__#"
                + LINE + "/" + ACTION + ",other.action#com.example.keep/other.action";
        String out = AurogonConfig.mergeAutostartRules(raw, Collections.singletonList(LINE), Collections.emptyList());
        assertTrue(out.startsWith(CIVET+";"));
        assertTrue(out.contains(LINE+"/other.action"));
        assertTrue(out.contains("com.example.keep/other.action"));
        assertFalse(out.contains(LINE+"/"+ACTION));
    }
    @Test public void unrelatedExistingPackageRuleIsPreservedAndNotCopiedIntoManagedSection() {
        String raw = CIVET + ";broadcastctrl:true#com.example.app/"+ACTION;
        String out = AurogonConfig.mergeAutostartRules(raw, null, Collections.singletonList("com.example.app"));
        assertEquals(1, count(out, "com.example.app/"+ACTION));
    }
    @Test public void falseBroadcastControllerDoesNotCountAsEffectiveRule() {
        assertFalse(AurogonConfig.hasFcmRule("broadcastctrl:false#"+LINE+"/"+ACTION, LINE));
    }
    @Test public void enabledRuleIsFoundAlongsideDisabledRuleSections() {
        String raw = "broadcastctrl:false#com.example.disabled/" + ACTION
                + ";broadcastctrl:true#" + LINE + "/" + ACTION;
        assertTrue(AurogonConfig.hasFcmRule(raw, LINE));
        assertFalse(AurogonConfig.hasFcmRule(raw, "com.example.disabled"));
        assertFalse(AurogonConfig.hasFcmRule(raw, "com.example"));
    }
    @Test public void allBroadcastRuleCountsAsAurogonControl() {
        assertTrue(AurogonConfig.hasFcmRule("broadcastctrl:true#" + LINE + "/ALL", LINE));
    }
    @Test public void appOpsPackageListIsIntersectionAndDeduplicated() {
        List<String> result = AurogonConfig.intersectAutostartAppOps(
                "com.one.app\ncom.two.app\ncom.one.app\ninvalid",
                "com.two.app\ncom.one.app\ncom.three.app");
        assertEquals(Arrays.asList("com.one.app", "com.two.app"), result);
    }
    @Test public void unknownAutostartPreservesOnlyPreviouslyManagedFcmPackages() {
        List<String> result = AurogonConfig.includeManagedUnknownPackages(
                Collections.singletonList("com.enabled.fcm"),
                Arrays.asList("com.existing.unknown", "com.new.unknown"),
                Collections.singletonList("com.existing.unknown"));
        assertEquals(Arrays.asList("com.enabled.fcm", "com.existing.unknown"), result);
    }
    @Test public void configuredPackagesReadOnlyEnabledAurogonSectionsAndHideMarker() {
        String raw = "broadcastctrl:true#" + AurogonConfig.MANAGER + "/"
                + AurogonConfig.MARKER_ACTION + "#com.example.fcm/" + ACTION
                + "#com.example.other/other.action;broadcastctrl:false#com.example.off/ALL";
        assertEquals(Arrays.asList("com.example.fcm", "com.example.other"),
                AurogonConfig.configuredPackages(raw));
        assertTrue(AurogonConfig.configuredPackages(null).isEmpty());
    }
    @Test public void staleManagedRuleForNonFcmAppIsRemovedOnNextSync() {
        String nonFcm = "com.leek.accounting";
        String raw = "broadcastctrl:true#com.reed.fcmguard/"
                + AurogonConfig.MARKER_ACTION + "#" + nonFcm + "/" + ACTION;
        String out = AurogonConfig.mergeAutostartRules(raw, null, Collections.emptyList());
        assertFalse(out.contains(nonFcm));
        assertEquals("", out);
    }
    @Test public void writerRaceAbortsWithoutClobberingConcurrentValue() {
        FakePort port = new FakePort(CIVET);
        port.secondRead = CIVET + ";concurrent:true#pkg.new/action";
        AurogonProtectionEngine.Result r = new AurogonProtectionEngine(port).sync(null, Collections.singletonList(LINE));
        assertFalse(r.verified); assertFalse(r.writeAttempted); assertTrue(r.error.contains("Concurrent"));
        assertEquals(CIVET, port.value);
    }
    @Test public void failedWriteIsReported() {
        FakePort port = new FakePort(CIVET); port.writeReturns = false;
        AurogonProtectionEngine.Result r = new AurogonProtectionEngine(port).sync(null, Collections.singletonList(LINE));
        assertFalse(r.verified); assertTrue(r.error.contains("returned false"));
    }
    @Test public void mismatchedReadBackIsNotReportedAsSuccess() {
        FakePort port = new FakePort(CIVET); port.readBackOverride = "other";
        AurogonProtectionEngine.Result r = new AurogonProtectionEngine(port).sync(null, Collections.singletonList(LINE));
        assertFalse(r.verified); assertTrue(r.error.contains("Read-back"));
    }
    @Test public void exceptionIsReported() {
        FakePort port = new FakePort(CIVET); port.throwOnWrite = true;
        AurogonProtectionEngine.Result r = new AurogonProtectionEngine(port).sync(null, Collections.singletonList(LINE));
        assertFalse(r.verified); assertTrue(r.error.contains("SecurityException"));
    }
    @Test public void verifiedRepeatIsNoOp() {
        FakePort port = new FakePort(CIVET);
        AurogonProtectionEngine engine = new AurogonProtectionEngine(port);
        assertTrue(engine.sync(null, Collections.singletonList(LINE)).verified);
        int writes = port.writes;
        assertTrue(engine.sync(AurogonConfig.managedPackages(port.value), Collections.singletonList(LINE)).verified);
        assertEquals(writes, port.writes);
    }

    private int count(String text, String token) {
        int n=0, i=0; while ((i=text.indexOf(token,i))>=0) { n++; i+=token.length(); } return n;
    }
    private static final class FakePort implements AurogonProtectionEngine.Port {
        String value; String secondRead; String readBackOverride; boolean writeReturns=true,throwOnWrite; int reads,writes;
        FakePort(String value){this.value=value;}
        public String read(){reads++; if(reads==2 && secondRead!=null)return secondRead; if(readBackOverride!=null && writes>0)return readBackOverride; return value;}
        public boolean write(String v){writes++; if(throwOnWrite)throw new SecurityException("denied"); if(writeReturns)value=v; return writeReturns;}
    }
}
