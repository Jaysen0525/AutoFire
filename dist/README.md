# 编译产物

本目录存放**已编译好的安装包**，方便不想自己编译的人直接下载使用。

## AotuFire-v2.6.apk

| 项目 | 值 |
|---|---|
| 版本 | 2.6 |
| 大小 | 2,255,929 字节（2.15 MB） |
| SHA-256 | `66FA5A54E6C6707EE9960B9D86DB1B25DDA5F154971043B05CEF71C82D1FC919` |
| 签名证书 SHA-256 | `5D:69:1D:9F:35:FF:76:1C:4F:E8:49:97:C5:DD:87:DC:60:2C:98:A1:78:5E:CA:55:04:50:67:8F:A2:8C:87:91` |
| 证书主体 | `CN=AotuFire, OU=Personal Build, O=Personal, L=Home, ST=Home, C=CN` |
| 对应源码 | 本仓库 `v2.6` 标签 |
| 最低系统 | Android 11（API 30） |

**下载直链**

- GitHub：`https://github.com/Jaysen0525/AutoFire/raw/main/dist/AotuFire-v2.6.apk`
- Gitee：`https://gitee.com/jaysen_chou/auto-fire/raw/main/dist/AotuFire-v2.6.apk`

---

## 下载后建议先核对指纹

APK 在传输途中是否被替换，比对哈希值即可知道：

```powershell
# Windows
Get-FileHash .\AotuFire-v2.6.apk -Algorithm SHA256
```

```bash
# macOS / Linux
shasum -a 256 AotuFire-v2.6.apk
```

应输出上面表格里的 `66FA5A54...FC919`。
更完整的验证方法（含签名校验、在线多引擎扫描）见仓库根目录的 [`安全验证说明.md`](../安全验证说明.md)。

---

## 两个必须说清的事实

**1. 从源码重新编译，哈希不会一样**

Android 的打包过程会写入时间戳等信息，**同样的源码编译两次，得到的 APK 哈希也不同**（构建不可复现）。
所以"重新编译然后比对哈希"**不是**有效的验证方式 —— 哈希只用于验证"文件在传输途中没被换过"。

**2. 从源码重新编译，签名也不同**

这个包用的是作者本机的签名密钥（私钥不在仓库里）。你自己编译出来的包虽然功能相同，
但签名不同，**无法覆盖安装这个包**，需要先卸载。

如果你只是想要一份自己改过的版本，这没问题；如果你要发布给别人用，
请用你自己的密钥签名并公布你自己的证书指纹。

---

## 为什么不用 Release 附件

GitHub / Gitee 的 Release 附件功能需要 API Token 才能自动上传。
直接放在仓库里的话，两个平台的行为一致、分享链接也最简单，
代价是每次发新版需要替换这个文件并更新上面的哈希。
