> **Bản dịch tiếng Việt** của tài liệu "Xiaomi China HyperOS: GMS/FCM screen-off disconnection investigation".
> Tác giả gốc: **dingwen07** — repo [hyperos-fcm-fix](https://github.com/dingwen07/hyperos-fcm-fix), tài liệu gốc: [docs/xiaomi-hyperos-gms-fcm-greezer-investigation.md](https://github.com/dingwen07/hyperos-fcm-fix/blob/main/docs/xiaomi-hyperos-gms-fcm-greezer-investigation.md).
> Giấy phép gốc: **GPL-3.0**. Bản dịch được phân phối theo cùng giấy phép, kèm ghi công cho tác giả gốc. Tài liệu gốc (tiếng Anh) luôn là phiên bản có giá trị quyết định khi có khác biệt về mặt thuật ngữ.
> Thuật ngữ kỹ thuật: *freeze* = đóng băng (tạm dừng tiến trình), *thaw* = rã đông (phục hồi tiến trình), *allowlist* = danh sách cho phép, *whitelist* = danh sách trắng. Các định danh bị ẩn hóa trong bản gốc (`<owner-gms-uid-a>`, `<timestamp>`…) được giữ nguyên.

# Xiaomi HyperOS Trung Quốc: Điều tra hiện tượng ngắt kết nối GMS/FCM khi tắt màn hình

Ngày: 07/2026 (bỏ qua ngày thử nghiệm chính xác; múi giờ Asia/Singapore)

> Ghi chú về việc ẩn hóa dữ liệu: tên mã thiết bị, model và định danh firmware, UID theo từng thiết bị, định danh app thử nghiệm không liên quan, endpoint mạng nội bộ, mốc thời gian chính xác, bộ đếm tích lũy riêng của từng thử nghiệm và vân tay nhị phân đã được xóa hoặc thay thế. Tên package công khai, UID shell cố định của Android, các lệnh, cấp API, phiên bản phần mềm và hành vi kỹ thuật được giữ lại vì cần thiết để tái lập và đánh giá các kết luận.

## Kết luận tóm tắt

Trên hai bản HyperOS Trung Quốc lỗi được thử nghiệm trong báo cáo này, nguyên nhân gây gián đoạn FCM kéo dài là do Xiaomi đóng băng UID của `com.google.android.gms` trong Greezer. Không cần đổ lỗi cho mạng LAN, định tuyến fake-IP, Android Doze, hay tường lửa `gms_wall` của PowerKeeper để giải thích hiện tượng ngắt kết nối quan sát được.

Có ít nhất hai đường đóng băng (freeze) Greezer riêng biệt ảnh hưởng đến GMS trên các bản này:

1. Một bộ giới hạn GMS tường minh mới hơn trong `GreezeManagerService`. Khi GMS trở nên hoạt động trong lúc màn hình tắt, nó chờ 10 giây, gỡ `com.google.android.gms` khỏi danh sách cho phép thông thường của Aurogon, và yêu cầu đóng băng nhanh (quick freeze) ngay lập tức.
2. Đường `PowerStrategyMode` riêng biệt. Các chuyển đổi tiền cảnh→hậu cảnh lên lịch đóng băng với lý do `tobg`; các lần rã đông do hệ thống kích hoạt có thể bị theo sau bởi các lần đóng băng với lý do `from system`.

Chính sách **Không giới hạn (No restrictions)** theo từng package của Xiaomi cấp dữ liệu cho một tập hợp khác, `Settings.System.MILLET_NO_RESTRICT_APP`. Cả phần cài đặt đóng băng nhanh của Aurogon lẫn `PowerStrategyMode` đều tham chiếu tập hợp này. Việc thêm chính `com.google.android.gms` vào đó đã khiến thiết bị thử nghiệm ngừng đóng băng GMS sau khi khóa màn hình và giữ kết nối TCP của FCM luôn được thiết lập.

Đặt **Google Play Store** (`com.android.vending`) thành Không giới hạn **không** thêm `com.google.android.gms` vào tập hợp đó. Nó chỉ tắt một tính năng kiểm soát mạng GMS riêng của PowerKeeper, vì tính năng đó chủ ý dùng chính sách của Play Store làm công tắc chính hiển thị cho người dùng. Điều này giải thích vì sao "Play Store Không giới hạn" có thể tắt tường lửa GMS của Xiaomi nhưng vẫn không ngăn được Greezer đóng băng Play services.

Giải pháp không cần root đã được thử nghiệm vì vậy là:

- Dùng Shizuku/shell để đảm bảo `com.google.android.gms` có mặt trong `MILLET_NO_RESTRICT_APP`, giữ nguyên mọi package hiện có.
- Kiểm tra và khôi phục lại bất cứ khi nào PowerKeeper dựng lại cài đặt này từ cơ sở dữ liệu chính sách riêng tư của nó.
- Tùy chọn: chạy thêm `dumpsys greezer IM GMS disable` sau mỗi lần `system_server` khởi động như một lớp phòng thủ sâu (defense in depth) trên các bản ROM có chứa bộ giới hạn GMS tường minh.

Cách này không yêu cầu tự động hóa UI, root, thay thế PowerKeeper, hay tắt Greezer toàn cục.

## Chuẩn bằng chứng

Các tuyên bố trong báo cáo này dựa trên một hoặc cả hai nguồn:

- Thử nghiệm ADB trực tiếp trên ba thiết bị được kết nối.
- Code được dịch ngược (decompile) lấy từ `miui-services.jar`, APK PowerKeeper, và APK Google Play services của chính các thiết bị đó.

Ở những chỗ code hàm ý điều gì đó nhưng chưa được cô lập trong một thử nghiệm trên thiết bị, nội dung được gán nhãn là "có code chứng minh" (code-backed) thay vì "đã kiểm chứng độc lập". Code dịch ngược có thể chứa các artifact về tên gọi hoặc luồng điều khiển, nên các kết luận mạnh nhất đều dùng phương pháp đơn giản và được hành vi thực tế trên máy xác nhận chéo.

## Các thiết bị được so sánh

| Nhãn | Thiết bị/model | Bản HyperOS | Android | PowerKeeper | Google Play services | Kết quả trước khi can thiệp |
|---|---|---|---:|---:|---:|---|
| Thiết bị A | Đã ẩn | Bản Trung Quốc A (đã ẩn) | 16 / API 36 | 4.2.00 | 26.26.34 | GMS chính bị đóng băng; FCM kết nối lại khi mở khóa |
| Thiết bị B | Đã ẩn | Bản Trung Quốc B (đã ẩn) | 16 / API 36 | 4.2.00 | 26.26.34 | GMS chính không bị đóng băng; FCM sống sót qua khóa màn hình |
| Thiết bị C | Đã ẩn | Bản Trung Quốc C (đã ẩn) | 16 / API 36 | 4.2.00 | 26.26.34 | GMS chính bị đóng băng cho đến khi mục "Không giới hạn" ẩn được thêm vào |

Cả ba thiết bị đều báo vùng `CN`.

UID GMS chính của từng thiết bị:

- Thiết bị A: `<owner-gms-uid-a>`
- Thiết bị B: `<owner-gms-uid-b>`
- Thiết bị C: `<owner-gms-uid-c>`

Thiết bị A và C còn có UID GMS của user-999/XSpace (`<xspace-gms-uid-a>` và `<xspace-gms-uid-c>`). FCM của user chính là đối tượng chính; bộ đếm của user nhân bản (clone) được giữ tách riêng.

## Tách bạch các cơ chế

HyperOS chứa nhiều thế hệ kiểm soát nền chồng lấn lên nhau. Các tên tương tự nhau như "allowlist", "whitelist", "freeze", "GMS control" không có nghĩa giống nhau.

```text
Giao diện Chi tiết ứng dụng của Xiaomi
    |
    | bgControl = "noRestrict" cho đúng package đó
    v
Cơ sở dữ liệu riêng tư UserConfigure của PowerKeeper
    |
    | ActiveStateController.dealNoRestrictApp()
    v
Settings.System.MILLET_NO_RESTRICT_APP
    |
    +--> Bộ lọc đóng băng nhanh Aurogon
    |
    +--> PowerStrategyMode / PolicyMaker / AurogonFilterManager

Tách riêng:

GmsObserver của PowerKeeper
    +--> chuỗi tường lửa gms_wall
    +--> bộ chặn DNS theo UID
    +--> xử lý wakelock/alarm/backup
    ^
    | công tắc hiển thị cho người dùng là bgControl của Play Store

Tách riêng:

AOSP DeviceIdle / Doze / app standby

Tách riêng:

Mức độ quan trọng tiến trình GMS (BFGS), binding trust-agent,
binding notification-listener, vai trò thanh toán mặc định
```

Bản sửa cuối cùng hoạt động thông qua `MILLET_NO_RESTRICT_APP`. Các cơ chế khác là ngữ cảnh chẩn đoán hữu ích nhưng không thể thay thế khoản miễn trừ Greezer dành riêng cho package này.

## Đường 1: mục "Không giới hạn" trong Chi tiết ứng dụng của Xiaomi

### Đường đi trong code

PowerKeeper định nghĩa:

```java
UserConfigure.BG_CONTROL_NO_RESTRICT = "noRestrict";
```

`ActiveStateController` theo dõi `UserConfigure.CONTENT_URI` riêng tư của PowerKeeper. Khi một dòng thay đổi, nó kiểm tra xem `bgControl` của đúng package đó có bằng `noRestrict` không, cập nhật một thuộc tính UID nội bộ, và gọi `dealNoRestrictApp()`.

`dealNoRestrictApp()` truy vấn tất cả các dòng user-0 có `bgControl = noRestrict`, dựng một tập tên package, và ghi kết quả phân tách bằng dấu phẩy vào:

```text
Settings.System.MILLET_NO_RESTRICT_APP
```

Nguồn đã pull liên quan:

- `work/powerkeeper/<device-a>/jadx/sources/com/miui/powerkeeper/provider/UserConfigure.java`
- `work/powerkeeper/<device-a>/jadx/sources/com/miui/powerkeeper/provider/UserConfigureHelper.java`
- `work/powerkeeper/<device-a>/jadx/sources/com/miui/powerkeeper/controller/ActiveStateController.java`, đặc biệt dòng 536-551 và 579-619

### Cách Greezer tiêu thụ cài đặt

`AurogonImmobulusMode` đăng ký một content observer cho `MILLET_NO_RESTRICT_APP`, phân tích theo dấu phẩy, cắt khoảng trắng, và lưu các tên package vào `mNoRestrictAppSet`.

Nó kiểm tra tập hợp này ở nhiều đường đóng băng. Cụ thể, `lambda$triggerQuickFreeze$0()` trả về ngay trước khi đóng băng nếu package đích nằm trong `mNoRestrictAppSet`.

`AurogonFilterManager` cũng định nghĩa `MSG_FILTER_NO_RESTRICT_CASE = 64`. `PolicyMaker.isAllowFreeze()` gọi:

```java
AurogonFilterManager.getInstance().filter(uid, pkgName, 150112)
```

`150112` chứa bit `64`, nên việc thuộc `mNoRestrictAppSet` khiến `PolicyMaker` trả về `CANNOT_FREEZE`. Điều này bảo vệ các đường của `PowerStrategyMode`, bao gồm cả `tobg` và `from system`.

Nguồn đã pull liên quan:

- `work/greezer/<device-a>/AurogonImmobulusMode.java`, đặc biệt dòng 277, 1391-1405, 1990-1992 và 2059-2077
- `work/decompiled/miui-services/sources/com/miui/server/greeze/AurogonFilterManager.java`, đặc biệt dòng 28 và 84-87
- `work/decompiled/miui-services/sources/com/miui/server/greeze/power/PolicyMaker.java`, đặc biệt dòng 183-200

### Tính đặc thù theo package

Cài đặt này đặc thù theo package. Đặt Play Store thành Không giới hạn tạo ra:

```text
com.android.vending
```

trên Thiết bị A. Nó **không** tạo ra `com.google.android.gms`.

Trước khi sửa, Thiết bị C chứa:

```text
com.android.vending, com.example.testapp
```

Sau khi shell thêm vào, nó chứa:

```text
com.android.vending, com.example.testapp, com.google.android.gms
```

Sự phân biệt theo đúng package này là lý do trung tâm khiến "Play Store Không giới hạn" không giữ được FCM sống sót.

### Bộ chọn từng app thực sự lưu gì

Giao diện SecurityCenter đưa ra bốn lựa chọn, nhưng chúng không phải là AppOps chuẩn của Android. `PowerDetailActivity` gửi một package, một user ID và một chuỗi qua giao diện Binder riêng tư `IPowerKeeper`:

| Lựa chọn trên UI | Giá trị Binder | `userTable.bgControl` của PowerKeeper |
|---|---|---|
| Tiết kiệm pin / thông minh (khuyến nghị) | `miui_auto` | `miuiAuto` |
| Không giới hạn | `no_restrict` | `noRestrict` |
| Giới hạn app nền | `restrict_bg` | `restrictBg` |
| Giới hạn hoạt động nền | `no_bg` | `noBg` |

`restrictBg` còn lưu thêm một độ trễ, mặc định 10 phút. Ba cấu hình còn lại dùng `bgDelayMin = -2` trên bản được thử nghiệm.

Nguồn liên quan:

- `work/decompiled/securitycenter/sources/com/miui/powercenter/legacypowerrank/PowerDetailActivity.java`, đặc biệt `h1()` và `onPreferenceClick()`
- `work/powerkeeper/<device-a>/jadx/sources/com/miui/powerkeeper/provider/PowerSaveConfigureManager.java`
- `work/powerkeeper/<device-a>/jadx/sources/com/miui/powerkeeper/provider/UserConfigure.java`

### Một lựa chọn trong CSDL tỏa ra nhiều bộ điều khiển

Giá trị `bgControl` duy nhất được biên dịch thành một kịch bản `PowerKeeperAppConfigure`. Một thay đổi nội dung của dòng tương ứng dựng lại đối tượng đã biên dịch của package đó và gọi `setAppConfigureUidPolicy()`. Phương thức này phân phối các chính sách phái sinh đến tất cả các phân hệ sau:

1. bộ kiểm tra app-activity/data
2. bộ kiểm tra quy tắc vị trí nền
3. bộ kiểm tra quy tắc app bị đóng băng kiểu cũ
4. bộ điều khiển kill-process và bộ kiểm tra quy tắc của nó
5. bộ điều khiển sensor và bộ kiểm tra quy tắc của nó
6. bộ điều khiển trạng thái hoạt động (active-state)
7. bộ kiểm tra quy tắc background-idle
8. bộ kiểm tra quy tắc DeviceIdle
9. bộ kiểm tra quy tắc app-idle/app-standby
10. bộ điều khiển app-cluster

Phần cài đặt thực hiện 12 lời gọi setter vì kill và sensor mỗi cái có một cài đặt cấp bộ điều khiển ngoài cài đặt cấp bộ kiểm tra quy tắc. Vì vậy cài đặt này là một hồ sơ (profile) ảnh hưởng đến nhiều chính sách, chứ không phải bí danh cho một AppOp hay một cờ tối ưu hóa pin của Android.

Hồ sơ này còn có hai tác dụng phụ trực tiếp quan trọng:

- Giá trị CSDL theo nghĩa đen là `noRestrict` khiến `ActiveStateController` ghi nhận UID là "không bị giới hạn bởi người dùng" và dựng lại `Settings.System.MILLET_NO_RESTRICT_APP` từ tất cả các dòng `noRestrict` của user-0.
- Kịch bản 8 cấp cho bộ kiểm tra quy tắc DeviceIdle chính sách bằng 0, khiến `DeviceIdleController` thêm app vào danh sách trắng DeviceIdle của người dùng trên Android.

Các chính sách số phái sinh là đặc thù theo bộ điều khiển. Cùng một con số không phải lúc nào cũng dẫn đến cùng một hành động cuối cùng ở kernel/framework, nên không được coi số kịch bản là một mức giới hạn phổ quát.

Nguồn liên quan:

- `PowerKeeperConfigureManager.setAppConfigureUidPolicy()`, dòng 847-863 trong nguồn đã pull
- `PowerKeeperAppConfigure.fillScenarioContent()` và các phương thức `to*Params()` của nó
- `ActiveStateController`, `DeviceIdleController`, `BgIdleController`, `FrozenAppController`, `SensorController` và `AppStandbyController`

### Thử nghiệm có kiểm soát: chuyển Test app từ thông minh sang Không giới hạn

Thiết bị C cung cấp một thử nghiệm trước/sau sạch sẽ chỉ dùng thao tác UI của người dùng.

Với Test app ở chế độ thông minh/khuyến nghị:

```text
userTable: com.example.testapp | miuiAuto | -2
kịch bản đã biên dịch: 2
thuộc MILLET_NO_RESTRICT_APP: không
thuộc danh sách trắng DeviceIdle của user: không
RUN_ANY_IN_BACKGROUND: allow
```

Sau khi người dùng chọn Không giới hạn:

```text
userTable: com.example.testapp | noRestrict | -2
kịch bản đã biên dịch: 8
thuộc MILLET_NO_RESTRICT_APP: có
DeviceIdle: user,com.example.testapp,<test-app-uid>
RUN_ANY_IN_BACKGROUND: allow
```

Vậy chỉ một thao tác UI này đã chứng minh được thay đổi bốn thứ: dòng riêng tư của PowerKeeper, hồ sơ bộ điều khiển đã biên dịch, tập package MILLET/Greezer của Xiaomi, và danh sách trắng DeviceIdle của Android. Nó **không** thay đổi AppOp nền chuẩn, vốn là `allow` cả trước lẫn sau.

### Vì sao bộ chọn bị ẩn với GMS

Trên bản này, đường ẩn là một bộ lọc chung "app không có launcher" chứ không phải một điều kiện chỉ dành cho GMS trong màn hình chi tiết pin:

1. `PowerKeeperConfigureManager.initUserConfigure()` kiểm tra mọi package được kiểm soát bằng `pkgHasIcon()`.
2. `Utils.pkgHasIcon()` chính xác là `PackageManager.getLaunchIntentForPackage(pkg) != null`.
3. Các package không có launch intent được ghi vào cài đặt `user_de_configured_apps` phân tách bằng dấu hai chấm.
4. `PowerDetailActivity` của SecurityCenter kiểm tra danh sách đó trước khi hỏi PowerKeeper về giá trị hiện tại theo app. Nếu package có trong danh sách, task truy vấn trả về `null` và cả nhóm chính sách chọn một (single-choice) bị ẩn toàn bộ.

Kiểm tra trên thiết bị khớp với code:

```text
com.google.android.gms: No activity found
com.example.testapp: com.example.testapp/.MainActivity
```

Dù vậy GMS vẫn có một dòng `userTable` bình thường. Trên Thiết bị C giá trị hiện tại của nó hiển thị trực tiếp trong dump của chính PowerKeeper:

```text
com.google.android.gms | miuiAuto | -2
```

`PowerSaveConfigureManager.getPowerSaveAppConfigure()` chứa một tiện ích di chuyển (migration): ở lần truy vấn UI đầu tiên, một app `miuiAuto` đã bị AOSP bỏ qua trong tối ưu hóa pin sẽ được tự động đổi thành `noRestrict`. Điều này không giúp gì cho GMS ở đây vì task của SecurityCenter trả về sớm với package nằm trong `user_de_configured_apps` và không bao giờ thực hiện truy vấn. Việc dòng GMS thực tế vẫn là `miuiAuto` bất chấp nó được AOSP miễn trừ `system/excidle` xác nhận rằng việc tự nâng cấp này chưa xảy ra.

### `miuiAuto` nghĩa là gì cụ thể với GMS

Bộ biên dịch kịch bản của PowerKeeper có một ngoại lệ tường minh cho lõi GMS. Với một app bên thứ ba bình thường, `miuiAuto` trên cấu hình cân bằng được thử nghiệm trở thành kịch bản 2. Với `GmsCoreUtils.isGmsCoreApp(packageName)`, cùng dòng `miuiAuto` đó trở thành kịch bản 0.

Giá trị thực tế trên Thiết bị C:

```text
Test app thông minh:     userTable=miuiAuto, kịch bản=2
GMS thông minh:          userTable=miuiAuto, kịch bản=0
Test app không giới hạn: userTable=noRestrict, kịch bản=8
```

`PowerKeeperAppConfigure.isNoRestrict()` trả về true cho cả kịch bản 0 và 8. Đây là lý do các bản PowerKeeper cũ có thể mô tả GMS là "thực tế không bị giới hạn" dù dòng CSDL của nó vẫn là `miuiAuto`.

Không được nhầm ngoại lệ kiểu cũ này với khoản miễn trừ Greezer mới hơn: `ActiveStateController.dealNoRestrictApp()` không dùng kịch bản biên dịch hay `isNoRestrict()`. Nó chỉ truy vấn các dòng mà `bgControl` theo nghĩa đen bằng `noRestrict`. Do đó kịch bản 0 của GMS **không** đưa `com.google.android.gms` vào `MILLET_NO_RESTRICT_APP`. Chính sự vắng mặt trong tập package này đã cho phép các đường Greezer mới hơn đóng băng nó.

### Shell có thể đặt GMS thành Không giới hạn hoàn toàn không?

Không: shell không thể đặt dòng CSDL quyền lực của PowerKeeper trên ROM này. Điều này được xác minh theo hai cách độc lập trên Thiết bị C:

```text
content query content://com.miui.powerkeeper.configure/userTable ...
SecurityException: requires miui.permission.powerkeeper.HIDDEN_MODE_PROVIDER
```

và:

```text
am startservice -n com.miui.powerkeeper/.PowerKeeperBackgroundService
Error: Requires permission com.miui.powerkeeper.permission.BIND_SERVICE
```

Cả hai quyền đều được khai báo `signatureOrSystem` (`signature|privileged` trong dump package đã cài). Một Shizuku UserService chạy với UID shell 2000 và không có được chúng. Binder cấu hình của PowerKeeper bị ràng buộc theo component, không được công bố như một dịch vụ gọi được toàn cục trong `service list`.

Shell có thể tái lập các phần hạ nguồn quan trọng cho GMS/FCM:

- ghi và theo dõi `Settings.System.MILLET_NO_RESTRICT_APP`;
- quản lý danh sách trắng DeviceIdle của Android (dù GMS đã có sẵn với cả hai trạng thái `system` và `system-excidle` trên Thiết bị C);
- quản lý trạng thái AppOps/app-standby thông thường nếu cần, dù cả hai đều không phải là khoản miễn trừ GMS bị thiếu trong trường hợp này.

Sau khi shell khôi phục mục MILLET cho GMS, PowerKeeper vẫn báo:

```text
userTable=miuiAuto
kịch bản đã biên dịch=0
```

Điều này chứng minh thao tác shell là một khoản miễn trừ Greezer hạ nguồn, không phải một mô phỏng đầy đủ hồ sơ `noRestrict` của UI. Tuy nhiên đối với lỗi FCM được thử nghiệm, đó là phần quyết định và đủ để chấm dứt hiện tượng đóng băng GMS khi tắt màn hình. Nó vẫn không bền vững (non-persistent) vì bất kỳ thay đổi dòng PowerKeeper sau này đều có thể dựng lại cài đặt chung từ bảng riêng tư và xóa các mục chỉ có nhờ shell.

### Phạm vi liên user: owner, XSpace và hồ sơ công việc (work profile)

`MILLET_NO_RESTRICT_APP` có kiểu lưu trữ và thực thi bất đối xứng:

- `Settings.System` là không gian cài đặt theo từng user.
- PowerKeeper chủ ý dựng key này chỉ từ các dòng `userTable` có `userId = 0` và `bgControl = noRestrict`.
- Greezer đọc key qua ngữ cảnh system-server của nó vào một `Set<String>` duy nhất. Tập hợp chỉ chứa tên package — không có user ID hay UID trong từng mục.
- Greezer phân giải mỗi UID ứng viên thành một `AurogonAppInfo` và kiểm tra `mNoRestrictAppSet.contains(app.mPackageName)`. Vì vậy mục package do owner nạp sẽ áp dụng cho package đó trong mọi Android user mà Greezer xử lý.

Observer được đăng ký cho `UserHandle.USER_ALL` (`-1`), nhưng callback của nó dùng `Settings.System.getString()` thường thay vì `getStringForUser(changedUserId)`. Trong system_server, điều này nạp lại giá trị của user owner. Một lệnh ghi chỉ dành cho user 999 hay một user hồ sơ quản lý có thể đánh thức observer, nhưng nó không trở thành một danh sách cho phép Greezer tách biệt theo hồ sơ.

Thiết bị C xác nhận sự phân tách lưu trữ:

```text
MILLET user 0:        com.example.testapp, com.google.android.gms
MILLET user 999:      không có

UID GMS owner:  <owner-gms-uid-c>
UID GMS XSpace: <xspace-gms-uid-c>
```

Thiết bị A có cả hồ sơ công việc quản lý (numeric user ID đã ẩn) lẫn XSpace (user 999). Nó thể hiện cùng kiểu phân tách:

```text
MILLET user 0:        com.android.vending, com.google.android.gms
MILLET work profile:  không có
MILLET user 999:      không có

UID GMS owner:        <owner-gms-uid-a>
UID GMS work profile: <work-profile-gms-uid-a>
UID GMS XSpace:       <xspace-gms-uid-a>
```

Bộ giới hạn GMS tường minh mới hơn củng cố kết luận liên user này. Nó liệt kê mọi `UserInfo`, dựng UID GMS của từng user bằng `UserHandle.getUid(userId, appId)`, và đẩy mọi UID đang chạy qua `triggerQuickFreeze()`. Phương thức đó lấy tên package và trả về trước khi đóng băng nếu package nằm trong tập `mNoRestrictAppSet` duy nhất. Vậy một mục owner cho `com.google.android.gms` bảo vệ owner, XSpace, và một work profile đang chạy khỏi đường đóng băng nhanh này.

Điều này cũng nghĩa là cơ chế MILLET không thể diễn đạt kiểu "chỉ không giới hạn trong work profile" hay "chỉ trong XSpace". Mức chi tiết của nó là tên package trên mọi user.

Hồ sơ PowerKeeper rộng hơn thì tinh tế hơn:

- Các hồ sơ quản lý thông thường có dòng `userTable` riêng và chính sách bộ điều khiển biên dịch của chúng được dựng với UID của chính hồ sơ đó.
- Phép chiếu MILLET vẫn bỏ qua các dòng hồ sơ đó vì `getNoRestrictApps()` cố định `userId = 0`.
- XSpace là trường hợp đặc biệt. PowerKeeper nhân bản `PowerKeeperAppConfigure` đã biên dịch của không gian chính sang UID XSpace tương ứng lúc khởi tạo và sau mỗi thay đổi dòng của owner. SecurityCenter cũng ánh xạ một chỉnh sửa chính sách XSpace về user không gian chính trước khi gọi PowerKeeper.
- DeviceIdle cuối cùng hoạt động theo app ID thay vì UID đầy đủ. PowerKeeper giữ trạng thái trung gian theo từng user nhưng OR nó qua các user trước khi sửa danh sách trắng DeviceIdle bên dưới, nên tác dụng phụ này cũng có thể lan qua các user cho các package dùng chung app ID.

Vì vậy, cho một bản sửa Shizuku trên ROM này, mục tiêu đúng tường minh là:

```sh
settings --user 0 put system MILLET_NO_RESTRICT_APP ...
```

Viết các bản sao riêng cho user 999 hay user work profile là không cần thiết cho Greezer và không thay thế được giá trị của owner. Bản sửa vẫn phải giữ nguyên mọi mục owner hiện có. Một thay đổi chính sách từ bất kỳ user nào cũng có thể thông báo cho PowerKeeper, vốn dựng lại phép chiếu của owner và có thể xóa các mục chỉ có nhờ shell.

Cuối cùng, đây là một khoản miễn trừ kiểu chế độ-bình-thường/lọc-package, không phải lời hứa phổ quát rằng không chế độ pin nào của Xiaomi có thể can thiệp vào UID. Một số đường hàng loạt của Immobulus chủ ý bỏ qua `mNoRestrictAppSet` khi chế độ Extreme đang bật. Đường đóng băng nhanh GMS được thử nghiệm và bộ lọc `PowerStrategyMode` thì tôn trọng mục package này.

### Hành vi ghi đè được xác nhận

PowerKeeper coi `MILLET_NO_RESTRICT_APP` như một phép chiếu được sinh ra từ bảng riêng tư `UserConfigure` của nó, chứ không phải một danh sách người dùng độc lập.

Điều này được xác nhận trực tiếp sau bản sửa đầu tiên:

1. `com.google.android.gms` được thêm vào qua shell.
2. Người dùng bật/tắt cài đặt nền Xiaomi của Test app.
3. PowerKeeper lập tức dựng lại cài đặt thành:

   ```text
   com.android.vending, com.example.testapp
   ```

4. Mục GMS thêm bằng shell đã biến mất.
5. Mục GMS sau đó được khôi phục.

Do đó một bản triển khai Shizuku bền vững phải kiểm tra lại cài đặt sau mọi thay đổi chính sách UI của PowerKeeper. Chỉ khôi phục lúc khởi động là không đủ.

## Đường 2: bộ giới hạn GMS tường minh mới hơn trong Greezer

### Các bản lỗi

Thiết bị A và Thiết bị C có các file dịch ngược `GreezeManagerService.java` giống hệt nhau từng byte:

```text
<sha256-omitted>
```

Các file `AurogonImmobulusMode.java` dịch ngược của chúng cũng giống hệt nhau:

```text
<sha256-omitted>
```

`GreezeManagerService` của chúng chứa một trường tường minh `mGmsLimitEnabled`, khởi tạo là `true`.

Khi một UID trở nên hoạt động, observer UID làm như sau:

```java
if (isGmsApp(uid) && !mScreenOn && !mHandler.hasMessages(10)) {
    mHandler.sendEmptyMessageDelayed(10, 10000L);
}
```

Message 10 gọi `triggerGMSLimitAction()`. Trên model Trung Quốc, khi `mGmsLimitEnabled` là true, phương thức đó:

1. Gỡ `com.google.android.gms` khỏi `mAllowList` thông thường của Aurogon.
2. Dựng UID GMS cho mọi Android user.
3. Gọi `triggerQuickFreeze(gmsUid, 0)` cho mỗi UID GMS đang chạy.

Nguồn đã pull liên quan:

- `work/greezer/<device-a>/GreezeManagerService.java`, dòng 416-424, 631 và 4258-4283
- Thiết bị C có code giống hệt ở các vị trí tương ứng.

### Lệnh dump lúc chạy

`AurogonImmobulusMode.dump()` chứa các lệnh ẩn:

```text
dumpsys greezer IM GMS disable
dumpsys greezer IM GMS enable
dumpsys greezer IM GMS limit
```

Hai lệnh đầu đặt `mGmsLimitEnabled` thành false hoặc true. `GreezeManagerService.dump()` dùng `DumpUtils.checkDumpPermission` chuẩn của Android, nên shell có thể dùng các tham số dump này.

Lệnh đã thử nghiệm:

```sh
dumpsys greezer IM GMS disable
```

Nó thành công trên Thiết bị 1 và 3, và `dumpsys greezer` sau đó báo:

```text
IM mGmsLimitEnabled : false
```

Cờ này được khởi tạo là `true` trong constructor của dịch vụ, và các phép gán khác duy nhất tìm thấy là các lệnh dump. Vì vậy đây là trạng thái lúc chạy (runtime state) và đặt lại khi `system_server` khởi động lại hay điện thoại reboot.

### Vì sao danh sách cho phép từ cloud không cứu được các bản lỗi

Cài đặt secure `immobulus_mode_switch_restrict` của cả ba thiết bị bắt đầu với `enable_24_allowlist` và chứa `com.google.android.gms`.

`AurogonImmobulusMode.updateCloudAllowList()` phân tích cài đặt đó thành `mCloudAllowList`, gộp vào `mAllowList`, và phân giải UID package vào `mAllowUidList`.

Trên các bản lỗi, `triggerGMSLimitAction()` tường minh gỡ GMS khỏi danh sách cho phép Aurogon trong bộ nhớ trước khi yêu cầu đóng băng nhanh. Chuỗi cloud secure bên dưới vẫn có thể hiển thị chứa GMS trong khi danh sách cho phép hiệu lực trong bộ nhớ không còn.

Danh sách cho phép cloud này khác với `mNoRestrictAppSet`. Bộ giới hạn GMS mới gỡ GMS khỏi danh sách đầu, nhưng `triggerQuickFreeze()` vẫn kiểm tra danh sách sau trước. Đó là lý do thêm GMS vào `MILLET_NO_RESTRICT_APP` mạnh hơn việc chỉ khôi phục danh sách cho phép Aurogon thông thường.

## Đường 3: PowerStrategyMode (`tobg` và `from system`)

Đây là một đường Greezer riêng biệt, không phải tường lửa của PowerKeeper và không phải bộ giới hạn GMS tường minh.

`GreezeManagerService.onForegroundActivitiesChanged(..., false)` lên lịch:

```java
dealRetryUid(uid, "tobg", PolicyMaker.BINDER_DELAYED_TIME)
```

sau một khoảng post ngắn. `BINDER_DELAYED_TIME` là 5000 ms. `ActionExecute.delayFreeze()` sau đó gọi `PolicyMaker.isAllowFreeze(uid)` và khi được cho phép thì đóng băng UID qua `GREEZER_MODULE_POWER`.

`ActionExecute.dealThawOther()` lên lịch cùng cơ chế với lý do `from system` khi module 1000 đánh thức một app.

Nguồn liên quan:

- `work/greezer/<device-a>/GreezeManagerService.java`, khoảng dòng 510-538
- `work/decompiled/miui-services/sources/com/miui/server/greeze/power/ActionExecute.java`, đặc biệt dòng 181-190, 234-267 và 340-435
- `work/decompiled/miui-services/sources/com/miui/server/greeze/power/PolicyMaker.java`

### Bằng chứng trên Thiết bị C

Chỉ tắt bộ giới hạn GMS tường minh là chưa đủ trên Thiết bị C. Ngay cả khi có:

```text
IM mGmsLimitEnabled : false
```

và sau khi thêm GMS vào `mAllowList` thông thường của Aurogon bằng `LM add`, lần khóa/mở khóa thủ công tiếp theo tạo ra:

```text
<timestamp> - THAW uid = <owner-gms-uid-c> ... reason : screen on caller : 1
<timestamp> - FZ uid = <owner-gms-uid-c> ... reason : tobg caller : 1
```

GMS bị đóng băng chín giây sau lần rã đông khi bật màn hình. Các chu kỳ sau cũng cho thấy:

```text
... FZ uid = <owner-gms-uid-c> ... reason : tobg caller : 1
... THAW uid = <owner-gms-uid-c> ... reason : Excute Service caller : 1000
... FZ uid = <owner-gms-uid-c> ... reason : from system caller : 1
```

Điều này chứng minh rằng:

- `IM GMS disable` chỉ kiểm soát một đường dành riêng cho GMS, không phải mọi đường Greezer.
- `LM add com.google.android.gms` sửa `mAllowList` thông thường của Aurogon nhưng không bảo vệ khỏi đường chính sách riêng của `PowerStrategyMode`.
- Tập Không giới hạn là bộ lọc dùng chung che phủ cả hai.

## Đường 4: bộ điều khiển mạng GMS riêng của PowerKeeper

PowerKeeper có một `GmsObserver` riêng. Đây là code có thật, nhưng không được nhầm với việc đóng băng tiến trình của Greezer.

### Nó kiểm soát gì

Trên các bản Trung Quốc, trạng thái mặc định của tính năng là bật:

```java
defaultState = !Build.IS_INTERNATIONAL_BUILD;
```

Nó khởi tạo một chuỗi điều khiển bởi MCD cho UID GMS:

```java
initGmsChain("gms_wall", gmsUid, "REJECT")
```

Khi PowerKeeper quyết định Google không liên lạc được, `onGoogleReachabilityChanged(false)` yêu cầu trạng thái chặn. `updateGmsState(true)` sau đó có thể:

- Bật `gms_wall` qua MCD.
- Đặt quy tắc DNS của UID GMS thành `deny` qua `dnsproxyd`.
- Chặn các wakelock liên quan GMS đã cấu hình.
- Hạn chế alarm và thông báo cho các callback đã đăng ký.
- Điều chỉnh hành vi Google backup.

Khi khả năng liên lạc trở lại, nó đảo ngược các kiểm soát này.

Nguồn liên quan:

- `work/powerkeeper/<device-a>/jadx/sources/com/miui/powerkeeper/utils/GmsObserver.java`
- `work/powerkeeper/<device-a>/jadx/sources/com/miui/powerkeeper/utils/NetdExecutor.java`
- `work/powerkeeper/<device-a>/jadx/sources/com/miui/powerkeeper/utils/OctVmNativeProxy.java`

### Vì sao "Play Store Không giới hạn" ảnh hưởng đường này

`GmsObserver.isGmsControlEnabled()` không đọc chính sách của package GMS. Nó đọc `com.android.vending`:

```java
UserConfigureHelper.getUserConfigureHelperByPkg(context, "com.android.vending")
```

và trả về false khi `bgControl` của Play Store là `noRestrict`.

Content observer sau đó bỏ chặn GMS nếu nó đang bị chặn, và các yêu cầu vào trạng thái chặn sau đó bị bỏ qua trong khi kiểm soát người dùng còn tắt.

Vì vậy:

- **"Play Store Không giới hạn" là công tắc tắt hiển thị cho người dùng của đường kiểm soát mạng GMS chuyên dụng này.**
- **Nó không phải là khoản miễn trừ Greezer cho `com.google.android.gms`.**

Người dùng đã thử "Play Store Không giới hạn" trên Thiết bị A. Chẩn đoán FCM vẫn hiển thị một kết nối mới 00:00/00:01 sau khi mở khóa. Danh sách package thực tế chỉ chứa `com.android.vending`, trong khi Greezer vẫn đóng băng được UID GMS. Hành vi này khớp với cả hai đường code và bác bỏ ý tưởng trước đó rằng "Play Store Không giới hạn" một mình tắt toàn bộ việc giới hạn GMS của Xiaomi.

### Quan hệ với `ERROR_IO_FIN`

Tường lửa chuyên dụng có thể làm gián đoạn mạng của GMS khi bật, nhưng `ERROR_IO_FIN` quan sát được không chứng minh rằng nó là thủ nhân của trường hợp này.

Trên Thiết bị C, lịch sử Greezer ghi nhận lần đóng băng `tobg` trước khi socket FCM biến mất. Một tiến trình bị đóng băng có thể để socket TCP hiện có còn hiển thị trong một thời gian ngắn; kết nối sau đó có thể bị đóng trong khi client vẫn không thể chạy. Vậy dòng thời gian được giải thích trọn vẹn bởi việc đóng băng tiến trình, không cần một sự kiện tường lửa.

Kết luận đúng phải giới hạn trong:

- Xiaomi có một bộ điều khiển tường lửa/DNS GMS riêng trong code.
- "Play Store Không giới hạn" tắt hành vi chặn do người dùng kiểm soát của nó.
- Lỗi FCM kéo dài khi tắt màn hình được thử nghiệm vẫn tồn tại vì Greezer vẫn đóng băng GMS.
- Không có tuyên bố rằng `gms_wall` không bao giờ có thể ảnh hưởng các lỗi khác.

### Phát hiện MCD và SELinux

`NetdExecutor` gửi MCD Binder transaction 8 tới dịch vụ `miui.whetstone.mcd` với các tham số như:

```text
sudebug set_chain_state gms_wall disable
```

Thiết bị chứa `/system_ext/bin/mcd`, sở hữu bởi `root:shell` và nhãn `u:object_r:mcd_exec:s0`.

Chính sách SELinux đã pull chứa:

- `allow shell mcd_exec ... execute`
- `allow shell mcd process transition`
- `typetransition shell mcd_exec process mcd`
- `allow mcd self capability net_admin`

Điều này khiến phương án dự phòng trạng thái chuỗi dựa trên MCD được code và chính sách hỗ trợ mà không cần root. Nó không cần thiết cho bản sửa Greezer thành công, và nó không tương đương với việc tắt quy tắc `dnsproxyd` theo UID riêng biệt. PowerKeeper cũng có thể bật lại chuỗi sau một sự kiện reachability sau đó trừ khi kiểm soát người dùng của nó bị tắt. Vì vậy nó không nên là giải pháp chính.

## Đường 5: AOSP Doze và app standby

Trạng thái DeviceIdle của AOSP tách biệt với Greezer của Xiaomi.

Trên Thiết bị C, sau bản sửa thành công, `dumpsys deviceidle whitelist` đã hiển thị GMS ở cả hai trạng thái `system` và `system-excidle`. Nó cũng đã có trong danh sách trắng Android trước khi hiện tượng đóng băng Xiaomi được giải quyết. Dù vậy, Greezer vẫn đóng băng UID <owner-gms-uid-c>.

Điều này chứng minh danh sách trắng Doze của Android không ngăn được bộ đóng băng cgroup của Xiaomi đóng băng một app. Shell có thể thao tác trạng thái Doze và app-standby thông thường, nhưng làm vậy không thay thế được tập Không giới hạn của Xiaomi.

Tương tự, thử `am unfreeze --sticky <gms-pid>` trên Thiết bị C đang bị đóng băng trả về thành công từ ActivityManager, nhưng lần đóng băng Greezer của Xiaomi vẫn hiệu lực và không socket FCM nào quay lại. Cơ chế sticky-unfreeze của AOSP không ghi đè đáng tin cậy quyền sở hữu Greezer độc lập của Xiaomi.

## Danh sách cho phép Aurogon thông thường và cấu hình PowerKeeper cục bộ

### `mAllowList` so với `mNoRestrictAppSet`

Đây là hai bộ sưu tập khác nhau:

- `mAllowList` được ghép từ các danh sách cho phép Aurogon cục bộ và cloud. `LM add` sửa bộ sưu tập runtime này.
- `mNoRestrictAppSet` đến từ `MILLET_NO_RESTRICT_APP` và được cả đóng băng nhanh Aurogon lẫn bộ lọc của `PowerStrategyMode` kiểm tra.

`dumpsys greezer LM add com.google.android.gms` sửa thành công cái đầu nhưng không chặn được lần đóng băng `tobg` trên Thiết bị C. Không được trình bày nó như bản sửa hoàn chỉnh.

### `local.config` của PowerKeeper

Cả ba cấu hình PowerKeeper đã giải mã đều chứa cùng mục GMS:

```json
{
  "app_name": "com.google.android.gms",
  "added": true,
  "group_id": 5,
  "action_list": [
    {"action_key": "set_data_connection", "action_value": true},
    {"action_key": "set_location", "action_value": false},
    {"action_key": "location_delay_hot", "action_value": "-2"},
    {"action_key": "kill_delay_hot", "action_value": "-2"}
  ]
}
```

Cả ba cũng có một chuỗi mặc định `launch_restrict` chứa GMS. Các mục chung này không giải thích được vì sao chỉ hai thiết bị đóng băng GMS chính. Chúng là bằng chứng phủ hữu ích chống việc coi mọi mục cấu hình PowerKeeper kiểu cũ đều là nguyên nhân.

## Vì sao Thiết bị B hoạt động mà không có mục GMS Không giới hạn

Danh sách `MILLET_NO_RESTRICT_APP` hiện tại của Thiết bị B không chứa GMS, vậy mà số lần đóng băng GMS chính của nó bằng không:

```text
pkg: com.google.android.gms uid: <owner-gms-uid-b> frozenTime: 0 count: 0
```

`GreezeManagerService` của nó khác biệt thực chất so với cặp thiết bị lỗi:

- Không có trường `mGmsLimitEnabled`.
- Observer UID-active của nó chỉ so với `mGmsMultiUid`; nó không gọi `isGmsApp(uid)` cho mọi UID GMS chính.
- `triggerGMSLimitAction(boolean)` của nó chỉ thao tác trên `mGmsMultiUid`, thay đổi quy tắc wake-lock và mạng. Nó không gỡ GMS chính khỏi danh sách cho phép Aurogon và không gọi `triggerQuickFreeze()` cho UID GMS của mọi user.
- Danh sách cho phép cloud của nó vẫn chứa GMS, và bộ xử lý kiểu cũ này không gỡ GMS chính khỏi danh sách đó.

Hash của `GreezeManagerService.java` dịch ngược trên bản hoạt động:

```text
<sha256-omitted>
```

Hash của `AurogonImmobulusMode.java` trên bản hoạt động:

```text
<sha256-omitted>
```

So sánh này là bằng chứng mạnh rằng khác biệt quyết định nằm trong phần code Greezer của framework Xiaomi, chứ không phải phiên bản APK GMS, chuỗi phiên bản PowerKeeper, hay mạng LAN.

## So sánh bản build PowerKeeper

Hash APK đầy đủ khác nhau, nhưng file DEX PowerKeeper chính của các thiết bị lỗi thì giống hệt nhau:

```text
Thiết bị A classes.dex: <sha256-omitted>
Thiết bị C classes.dex: <sha256-omitted>
Thiết bị B classes.dex: <sha256-omitted>
```

Hash `local.config` đã giải mã cũng giống nhau cho cặp lỗi và khác với Thiết bị B:

```text
Thiết bị A: <sha256-omitted>
Thiết bị C: <sha256-omitted>
Thiết bị B: <sha256-omitted>
```

Tuy nhiên, khác biệt `GmsObserver.java` dịch ngược giữa PowerKeeper hoạt động và lỗi chỉ là một tham chiếu ký tự phân cách bị obfuscate. Logic tường lửa chuyên dụng về ngữ nghĩa là giống nhau. Đặc điểm phân biệt hoạt động/lỗi rõ nhất vẫn là code Greezer trong `miui-services.jar`.

## Mức độ quan trọng tiến trình, BFGS, trust agent, quyền đọc thông báo và vai trò thanh toán

Các yếu tố này được điều tra vì chúng có thể khiến GMS trông quan trọng đối với ActivityManager của Android. Chúng không tạo ra một khoản miễn trừ Greezer đáng tin cậy của Xiaomi.

### BFGS không phải danh sách Không giới hạn

`BFGS` là mức độ quan trọng tiến trình của Android gắn với trạng thái foreground-service bị bind. Nó có thể xuất hiện khi các thành phần framework đặc quyền bind dịch vụ bên trong GMS. Nó không tự động thêm `com.google.android.gms` vào `MILLET_NO_RESTRICT_APP`.

Bằng chứng đã thử nghiệm:

- Thiết bị C được cấu hình quyền truy cập thông báo của Google Play services và Extend Unlock bật, nhưng GMS vẫn bị đóng băng sau khi khóa.
- Đặt Google Pay làm app thanh toán mặc định trên Thiết bị A không tạo ra khoản miễn trừ Greezer bền vững.
- UID GMS của Thiết bị C xuất hiện trong tập `mCore` của Greezer và vẫn bị đóng băng lặp đi lặp lại.
- GMS chính của Thiết bị B không dựa vào quan sát `mCore` tương tự và có số lần đóng băng bằng không.

Vì vậy BFGS, một dịch vụ hệ thống bị bind, hay tư cách thành viên `mCore` có thể ảnh hưởng một số nhánh chính sách nhưng không cần thiết cũng không đủ cho khoản miễn trừ quan sát được.

### `LockingTrustAgentService`

Code GMS 26.26.34 đã pull cho thấy:

- `LockingTrustAgentService` là một proxy Chimera mỏng.
- `LockingTrustAgentChimeraService` kế thừa `TrustAgentService` của Android và tự đăng ký khi được tạo.
- `LockingIntentOperation` xử lý action nội bộ `com.google.android.gms.personalsafety.ACTION_LOCK_DEVICE`; khi trust agent và cổng tính năng khả dụng, nó gọi `lockUser()` và có thể hiển thị thông báo keyguard.

Đây là chức năng Personal Safety/khóa chống trộm. Framework trust của Android bind một trust agent đã cấu hình; việc bind có thể nâng mức độ quan trọng tiến trình GMS. Nó không phải là code cấp danh sách trắng PowerKeeper hay Greezer.

Nguồn liên quan:

- `work/gms_inspect/LockingTrustAgentService.java`
- `work/gms_inspect/LockingTrustAgentChimeraService.java`
- `work/gms_inspect/LockingIntentOperation.java`

### `PhoneHubNotificationListenerService`

Code đã pull cho thấy:

- `PhoneHubNotificationListenerService` là một proxy Chimera khác.
- Phần cài đặt kế thừa `NotificationListenerService` của Android.
- `onListenerConnected()` đọc các thông báo đang hoạt động.
- Các callback thông báo chọn các thông báo nhắn tin/gọi điện được hỗ trợ và chuyển tiếp thay đổi tới các callback Phone Hub/proximity.

Cấp quyền truy cập thông báo khiến framework notification-manager của Android bind listener đã bật, điều này cũng có thể nâng mức độ quan trọng tiến trình GMS. Nó dùng cho việc phản chiếu thông báo Phone Hub liên thiết bị, không phải một keepalive FCM hay danh sách trắng Greezer.

Nguồn liên quan:

- `work/gms_inspect/PhoneHubNotificationListenerService.java`
- `work/gms_inspect/PhoneHubNotificationListenerChimeraService.java`

## Quan sát mạng

Thiết bị Xiaomi được thử nghiệm không dùng VPN. Mạng LAN có thể truy cập Google trực tiếp. Một Pixel trên cùng LAN duy trì FCM rất lâu, nên mạng LAN và cấu hình fake-IP là các đối chứng hợp lệ.

Kết nối FCM của Xiaomi dùng đích fake-IP `<fake-ip>:5228`. Cùng đích đó được quan sát cả trước và sau bản sửa Greezer. Điều này phản bác việc định tuyến fake-IP là yếu tố phân biệt.

## Kết quả thử nghiệm có kiểm soát

### Thiết bị A: tắt bộ giới hạn GMS tường minh

Lệnh:

```sh
dumpsys greezer IM GMS disable
```

Kết quả:

- `mGmsLimitEnabled` thành false.
- Trong khi thiết bị đã ở trạng thái dozing, socket FCM chính xác vẫn còn:

  ```text
  <lan-ip>:<ephemeral-port> -> <fake-ip>:5228
  ```

  trong khoảng năm phút.
- Số lần đóng băng GMS chính không thay đổi trong mẫu quan sát đó.

Điều này cho thấy bộ giới hạn tường minh quan trọng trên Thiết bị A. Nó không chứng minh lệnh này che phủ mọi đường đóng băng — điều mà Thiết bị C sau đó bác bỏ.

### Thiết bị C: tắt bộ giới hạn cộng danh sách cho phép Aurogon thông thường

Áp dụng:

```sh
dumpsys greezer IM GMS disable
dumpsys greezer LM add com.google.android.gms
```

Sau một lần khóa/mở khóa thủ công, GMS chính bị đóng băng với `reason : tobg`. Socket FCM xuất hiện ngắn sau khi thức rồi biến mất trong lúc GMS bị đóng băng.

Kết luận: kết hợp này không che phủ `PowerStrategyMode`.

### Thiết bị C: mục "Không giới hạn" ẩn

Giá trị `MILLET_NO_RESTRICT_APP` hiện có được giữ nguyên và `com.google.android.gms` được thêm vào.

Sau khi người dùng thủ công mở khóa rồi khóa điện thoại:

- Lần rã đông cuối của GMS chính ở `<timestamp>`, lý do `screen on`.
- Không có lần đóng băng GMS chính nào được ghi nhận trong khoảng xác minh.
- Bộ đếm đóng băng tích lũy của GMS chính không tăng.
- Tại `<timestamp>`, socket FCM vẫn là kết nối đã thiết lập:

  ```text
  <lan-ip>:<ephemeral-port> -> <fake-ip>:5228  ESTAB
  ```

- Người dùng độc lập xác nhận vấn đề đã được sửa.

Thiết bị vẫn có `mGmsLimitEnabled = false` trong thử nghiệm này, nên đây là một thử nghiệm trạng thái kết hợp. Code độc lập cho thấy `mNoRestrictAppSet` được kiểm tra trước khi đóng băng nhanh tường minh và bởi `PowerStrategyMode`; do đó cài đặt này che phủ các đường đã xác định ngay cả khi bộ giới hạn runtime sau này đặt lại.

### Thiết bị C: thử nghiệm ghi đè

Sau bản sửa, bật/tắt chính sách nền Xiaomi của Test app lập tức xóa mục GMS thêm bằng shell. Trong lúc mục đó vắng mặt, điện thoại bị khóa và Greezer ghi nhận:

```text
<timestamp> - FZ uid = <owner-gms-uid-c> ... reason : screen off caller : 1
```

Mục GMS được khôi phục sau đó, nhưng thay đổi một danh sách cho phép không rã đông được một UID đang bị đóng băng. GMS vẫn bị đóng băng cho đến khi một sự kiện Bluetooth chính đáng tạo ra:

```text
<timestamp> - THAW uid = <owner-gms-uid-c> ... reason : bluetooth caller : 1000
```

Không có lần đóng băng mới trong mẫu quan sát cuối, và đến `<timestamp>` FCM đã kết nối lại trên:

```text
<lan-ip>:<ephemeral-port> -> <fake-ip>:5228  ESTAB
```

Bộ đếm đóng băng tích lũy của GMS chính tăng thêm một do lần đóng băng trong khoảng thời gian mục bị thiếu.

Đây là một thí nghiệm xóa/phục hồi hữu ích:

- Khi mục GMS có mặt, lần xác minh sau khóa đầu tiên không có đóng băng mới và giữ được socket.
- Việc viết lại từ UI PowerKeeper đã xóa mục đó.
- GMS sau đó bị đóng băng khi tắt màn hình.
- Khôi phục mục đó ngăn một lần đóng băng khác sau khi lần đóng băng hiện có được rã đông chính đáng, và FCM kết nối lại.

Nó xác nhận nhu cầu về một bản sửa liên tục, kịp thời, idempotent thay vì một lần ghi duy nhất. Thao tác sửa nên chạy ngay khi cài đặt thay đổi; chỉ khôi phục mục đó muộn hơn không thể hoàn tác một lần đóng băng đã xảy ra.

## Ranh giới của shell và Shizuku

### Shell có thể làm gì

Đã thử nghiệm thành công:

- Đọc và ghi `Settings.System.MILLET_NO_RESTRICT_APP` bằng `settings`.
- Gọi `dumpsys greezer IM GMS disable`.
- Gọi `dumpsys greezer LM add ...` (dù không phải bản sửa hoàn chỉnh).
- Đọc bộ đếm/lịch sử Greezer qua `dumpsys`.
- Quản lý trạng thái Doze/app-standby AOSP thông thường.

### Shell không thể làm trực tiếp

`cmd greezer help` trả về một `SecurityException` nói rằng UID 2000 không có quyền `greezer`. Đường Binder shell-command chính thức bị hạn chế hơn đường dump của dịch vụ.

Manifest của PowerKeeper bảo vệ configuration provider đã export của nó bằng:

```text
miui.permission.powerkeeper.HIDDEN_MODE_PROVIDER
protectionLevel="signatureOrSystem"
```

Các dịch vụ điều khiển đã export dùng `com.miui.powerkeeper.permission.BIND_SERVICE`, cũng là `signatureOrSystem`. Một client Shizuku shell bình thường không thể đơn giản ghi một dòng `UserConfigure` ẩn cho GMS qua các giao diện này.

Đó là lý do con đường không-root thực tế là ghi cài đặt hệ thống hạ nguồn và sửa lại nó khi PowerKeeper ghi đè.

## Triển khai Shizuku được khuyến nghị

### Hành vi bắt buộc

Shizuku UserService nên triển khai một thao tác idempotent `ensureGmsNoRestrict()`:

1. Đọc `settings get system MILLET_NO_RESTRICT_APP` với tư cách shell.
2. Phân tích các mục phân tách bằng dấu phẩy và cắt khoảng trắng đúng như Greezer làm.
3. Nếu `com.google.android.gms` vắng mặt, thêm vào mà không xóa hay sắp xếp lại các package người dùng chọn một cách không cần thiết.
4. Ghi lại toàn bộ danh sách đã bảo toàn bằng `settings put system MILLET_NO_RESTRICT_APP ...`.
5. Đọc lại và xác minh.

Chạy thao tác đó:

- Khi app lấy lại quyền truy cập Shizuku.
- Sau khi boot, ngay khi Shizuku khả dụng.
- Liên tục qua một watchdog idempotent kịp thời trong khi Shizuku UserService có thể chạy.
- Sau khi người dùng thay đổi bất kỳ chính sách nền app Xiaomi nào.

Watchdog phải tránh ghi không cần thiết: so sánh tư cách thành viên đã chuẩn hóa trước và chỉ ghi khi GMS vắng mặt. Một Settings observer của tiến trình ứng dụng thông thường không phải là sự thay thế bền vững khi Xiaomi có thể giết tiến trình đó, trong khi Shizuku UserService của shell không thể dựa vào các API ngữ cảnh ứng dụng thông thường. Sự đánh đổi giữa các kích hoạt viết lại và watchdog được ghi nhận riêng trong [bản điều tra chuyên sâu `MILLET_NO_RESTRICT_APP`](xiaomi-millet-no-restrict-app-rewrite-investigation.md).

Bản sửa phải kịp thời. Nếu GMS bị đóng băng trong khoảng thời gian mục đó vắng mặt, khôi phục danh sách ngăn các quyết định chính sách sau này nhưng không rã đông được lần đóng băng hiện có. Con đường unfreeze shell/AOSP đã thử nghiệm không hiệu quả với Greezer, nên app nên báo cáo tình trạng đó và để người dùng thức/mở khóa thiết bị thay vì tự động hóa thao tác UI.

### Phòng thủ sâu tùy chọn

Trên các bản mà `dumpsys greezer` hiển thị `mGmsLimitEnabled`, cũng chạy:

```sh
dumpsys greezer IM GMS disable
```

sau khi `system_server` khởi động. Đây là một công tắc runtime dễ mất (volatile). Nó là phòng thủ sâu hữu ích và đã được thử nghiệm trực tiếp, nhưng không được thay thế bản sửa `MILLET_NO_RESTRICT_APP`.

### Không dùng làm bản sửa duy nhất

- Play Store Không giới hạn: tắt bộ điều khiển mạng GMS riêng biệt, không phải khoản miễn trừ package của Greezer.
- `dumpsys greezer LM add com.google.android.gms`: không che phủ `PowerStrategyMode`.
- Danh sách trắng DeviceIdle AOSP hay các lệnh app-standby: không ghi đè được bộ đóng băng của Xiaomi.
- `am unfreeze --sticky`: không ghi đè được một lần đóng băng Greezer hiện có.
- BFGS/quyền đọc thông báo/Extend Unlock/vai trò thanh toán mặc định: không miễn trừ GMS đáng tin cậy.
- Tắt toàn cục Immobulus/Greezer: rộng không cần thiết và không được bản sửa có mục tiêu đã thử nghiệm yêu cầu.

## Trạng thái đã thử nghiệm tại thời điểm báo cáo

Thiết bị C được để lại với:

```text
MILLET_NO_RESTRICT_APP = com.android.vending, com.example.testapp, com.google.android.gms
IM mGmsLimitEnabled = false
```

Bộ đếm đóng băng tích lũy cuối cùng của GMS chính bao gồm cả lần đóng băng `screen off` bị phơi có chủ ý trong thử nghiệm ghi đè; giá trị chính xác đã ẩn. Sau lần rã đông Bluetooth tiếp theo, FCM tái lập kết nối cổng 5228 và không có lần đóng băng thêm nào trong mẫu quan sát cuối.

Thiết bị A được để lại với `IM mGmsLimitEnabled = false`; danh sách Không giới hạn Xiaomi của nó chỉ chứa `com.android.vending`.

Thiết bị B không bị sửa đổi cho bản sửa này.

Các cờ runtime và cài đặt này có thể thay đổi sau khi reboot, `system_server` khởi động lại, cập nhật PowerKeeper/làm mới cloud, hay thay đổi chính sách của người dùng. Báo cáo ghi lại trạng thái quan sát được, không phải một sửa đổi ROM vĩnh viễn.

## Tuyên bố nguyên nhân cuối cùng

Với các thiết bị lỗi được thử nghiệm, nguyên nhân trực tiếp của việc mất FCM kéo dài khi tắt màn hình là Xiaomi Greezer đóng băng `com.google.android.gms`. Khoản miễn trừ cấp package quyết định là `MILLET_NO_RESTRICT_APP` của Xiaomi, không phải chính sách của Play Store, Android Doze, BFGS, một trust agent, một notification listener, hay danh sách cho phép cloud Aurogon thông thường.

Bộ điều khiển `gms_wall`/DNS của PowerKeeper là một cơ chế thật sự riêng biệt. "Play Store Không giới hạn" tắt bộ điều khiển đó, nhưng không chặn các đường Greezer mới hơn. Giữ `com.google.android.gms` trong tập "Không giới hạn" ẩn — và sửa lại mục này bất cứ khi nào PowerKeeper dựng lại tập đó — chính là giải pháp hẹp, đã thử nghiệm, không cần root.
