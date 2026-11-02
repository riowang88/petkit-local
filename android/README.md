# T6 无 NFC 工具（Android）

把 `addon/petkit_local/patchers/no_nfc.py` 这条路搬到手机：BLE 把设备临时指到手机，手机当本地服务器，经心跳 `run_cmd` 打补丁，再把设备交还官方云。手机不需要 root。设计和风险见仓库根目录的 `无NFC-安卓APK-开发方案.md`。

只支持 T6 固件 1.951。`NoNfcPatch.kt` 的偏移和 SHA 必须和 `no_nfc.py` 保持一致。

当前是阶段 1～5 的调试界面，没有上过真机。

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

两组测试默认跳过，需要时用环境变量打开：

- `NONFC_STOCK_DIR`：放 1.951 原件 `ble`、`ctrl` 的目录。证明这里的字节补丁和 Python 得到同一个目标 SHA。
- `NONFC_SSHD_PORT` + `NONFC_SSHD_KEYS`：本机 sshd 的端口和它读取的 `authorized_keys` 路径。证明生成的密钥能真实登录。

`core/` 是纯 Kotlin，没有安卓依赖，协议和编排都在 JVM 上测。`device/` 是 BLE、HTTP、SSH 和前台服务。
