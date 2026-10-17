package com.reed.fcmguard;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;

public class NightProtectionEngineTest {
    private static final String G = NightProtectionEngine.GMS;
    private static class Fake implements NightProtectionEngine.Port {
        String value; boolean permission=true, reject, mismatch, broadcastFail, readFail, persistenceFail;
        int writes, thaws, reconnects, reads, saves; long clock=100000;
        Runnable beforeRead;
        public String read() throws Exception {
            reads++; if(beforeRead!=null) beforeRead.run();
            if(readFail) throw new SecurityException("read denied"); return value;
        }
        public boolean write(String s) { writes++; if(!reject) value=mismatch?"other.writer":s; return !reject; }
        public boolean canWrite() { return permission; }
        public void thaw() { thaws++; if(broadcastFail) throw new SecurityException("thaw denied"); }
        public void reconnect() { reconnects++; if(broadcastFail) throw new SecurityException("reconnect denied"); }
        public long now() { return clock; }
        public void save(NightProtectionEngine.State s) { saves++; if(persistenceFail) throw new IllegalStateException("disk full"); }
    }
    private Fake f=new Fake();
    private NightProtectionEngine e=new NightProtectionEngine(f,new NightProtectionEngine.State());
    @Test public void nullList() { assertFalse(NightProtectionEngine.contains(null)); assertEquals(G,NightProtectionEngine.append(null)); }
    @Test public void emptyList() { assertEquals(G,NightProtectionEngine.append("")); }
    @Test public void presentIsExactNoAppend() { assertEquals("a_"+G+"_b",NightProtectionEngine.append("a_"+G+"_b")); }
    @Test public void substringIsNotMembership() { assertFalse(NightProtectionEngine.contains(G+".other")); }
    @Test public void tokensAreNotTrimmed() { assertFalse(NightProtectionEngine.contains(" "+G)); }
    @Test public void duplicatesAreNotAddedAndCleanupRemovesOnlyGms() {
        String s="a_"+G+"_"+G+"_b";
        assertEquals(s,NightProtectionEngine.append(s)); assertEquals("a_b",NightProtectionEngine.remove(s));
    }
    @Test public void preserveOtherPackagesAndOrder() { assertEquals("a_b_"+G,NightProtectionEngine.append("a_b")); }
    @Test public void underscoreInsidePackagesMatchesVendorSplitLimitation() { assertTrue(NightProtectionEngine.contains("pkg_with_underscore_"+G)); }
    @Test public void reloadPreservesEffectiveTokens() {
        for(String s:Arrays.asList(G,"a_"+G+"_",G+"__"))
            assertArrayEquals(s.split("_"),NightProtectionEngine.reload(s).split("_"));
    }
    @Test public void rejectedWriteIsFailure() { f.reject=true; e.configure(true); assertEquals(2,e.state.write); assertEquals(0,f.thaws); }
    @Test public void differingReadbackIsFailureEvenWhenGmsPresent() {
        f.beforeRead=()-> { if(f.writes>0) f.value=G+"_external"; };
        e.configure(true); assertEquals(2,e.state.write); assertTrue(e.state.uncertain); assertEquals(0,f.thaws);
    }
    @Test public void repeatedObserverChecksAreIdle() {
        e.configure(true); int writes=f.writes;
        for(int i=0;i<100;i++) e.maintain(true);
        assertEquals(writes,f.writes); assertEquals(1,f.thaws);
    }
    @Test public void selfWriteNotificationIsNoOp() { e.configure(true); e.maintain(true); assertEquals(1,f.writes); assertEquals(1,f.reconnects); }
    @Test public void raceBeforePutDefersWithoutOverwrite() {
        f.beforeRead=()-> { if(f.reads==2) f.value="new.writer"; };
        e.configure(true); assertEquals(0,f.writes); assertEquals("new.writer",f.value);
        e.maintain(true); assertEquals(0,f.writes);
        f.clock+=61000; e.maintain(true); assertEquals("new.writer_"+G,f.value);
    }
    @Test public void raceAfterPutIsNotImmediatelyRetried() {
        f.mismatch=true; e.configure(true);
        for(int i=0;i<30;i++) e.maintain(true);
        assertEquals(1,f.writes); assertEquals(0,f.thaws);
    }
    @Test public void disableCleansOnlyOwnedToken() {
        f.value="a_b"; e.configure(true); e.configure(false);
        assertEquals("a_b",f.value); assertFalse(e.state.cleanup);
        int writes=f.writes; e.maintain(true); assertEquals(writes,f.writes);
    }
    @Test public void cleanupUsesEmptyPutInsteadOfDeletion() { e.configure(true); e.configure(false); assertEquals("",f.value); }
    @Test public void emptyTokenOnlyCleanupWritesEmptyString() {
        assertEquals("",NightProtectionEngine.remove("_"+G+"___"));
    }
    @Test public void preexistingGmsIsNeverRemoved() { f.value=G; e.configure(true); e.configure(false); assertTrue(NightProtectionEngine.contains(f.value)); assertFalse(e.state.cleanup); }
    @Test public void failedCleanupRemainsRetryable() {
        e.configure(true); f.reject=true; e.configure(false); assertTrue(e.state.cleanup);
        f.reject=false; e.cleanup(false); assertFalse(e.state.cleanup); assertEquals("",f.value);
    }
    @Test public void externalChangeMakesOwnershipUncertain() {
        e.configure(true); f.value="external_"+G; e.configure(false);
        assertTrue(e.state.cleanup); assertEquals("external_"+G,f.value);
        e.cleanup(true); assertEquals("external",f.value); assertFalse(e.state.cleanup);
    }
    @Test public void broadcastsFailIndependently() {
        f.broadcastFail=true; e.configure(true); assertEquals(2,e.state.thaw);
        assertEquals(2,e.state.reconnect); assertEquals(1,f.reconnects); assertEquals(1,e.state.write);
    }
    @Test public void frozenAndSocketNeverReportedAsVerified() {
        e.configure(true); assertTrue(e.state.logs.stream().anyMatch(s->s.contains("actual thaw UNKNOWN")));
        assertTrue(e.state.logs.stream().anyMatch(s->s.contains("socket UNKNOWN")));
    }
    @Test public void restartRechecksAndReloadsOncePreservingOwnership() {
        e.configure(true); e=new NightProtectionEngine(f,e.state); e.maintain(true);
        assertEquals(2,f.writes); e.maintain(true); assertEquals(2,f.writes);
        e.configure(false); assertEquals("",f.value);
    }
    @Test public void disabledRestartNeverWrites() { e.check(); e.maintain(true); assertEquals(0,f.writes); }
    @Test public void permissionDenialDoesNotWrite() { f.permission=false; e.configure(true); assertEquals(0,f.writes); assertTrue(e.state.error.contains("WRITE_SETTINGS")); }
    @Test public void readFailureDoesNotWriteOrClaimReadSuccess() { f.readFail=true; e.configure(true); assertFalse(e.readOk); assertEquals(0,f.writes); }
    @Test public void checkIsAlwaysReadOnly() { e.configure(true); f.value="other"; int writes=f.writes; e.check(); assertEquals(writes,f.writes); assertEquals(1,f.thaws); }
    @Test public void manualBroadcastTestDoesNotModifyListAndIsRateLimited() {
        f.value="a"; e.testBroadcasts(); e.testBroadcasts(); assertEquals("a",f.value); assertEquals(0,f.writes); assertEquals(1,f.thaws);
    }
    @Test public void boundedLogAndClear() { for(int i=0;i<150;i++) e.check(); assertEquals(100,e.state.logs.size()); e.clearLogs(); assertTrue(e.state.logs.isEmpty()); }
    @Test public void cleanupPendingPreventsReenable() {
        e.configure(true); f.permission=false; e.configure(false); e.configure(true);
        assertFalse(e.state.enabled); assertTrue(e.state.cleanup);
    }
    @Test public void ownershipMustBeSavedBeforeWrite() {
        f.persistenceFail=true; try { e.configure(true); } catch(IllegalStateException expected) {}
        assertEquals(0,f.writes);
    }
    @Test public void deniedPermissionDoesNotPretendFrameworkRefreshWasAttempted() {
        f.permission=false; e.configure(true); assertEquals(0,e.state.reload);
    }
    @Test public void autoReadFailureHasAutomaticLogFlag() {
        e.configure(true); f.readFail=true; e.maintain(true);
        assertTrue(e.state.logs.get(e.state.logs.size()-1).contains("auto=true"));
    }
    @Test public void successfulManualBroadcastDoesNotEraseUnrelatedWriteFailure() {
        f.reject=true; e.configure(true); String failure=e.state.error;
        e.testBroadcasts(); assertEquals(failure,e.state.error);
    }
    @Test public void retryFailedBroadcastOnLaterFallbackWithoutRewrite() {
        f.broadcastFail=true; e.configure(true); f.broadcastFail=false; f.clock+=61000;
        e.maintain(true); assertEquals(1,f.writes); assertEquals(1,e.state.thaw); assertEquals(1,e.state.reconnect);
    }
}
