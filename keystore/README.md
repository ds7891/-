# 零 Secrets 签名（仓库内置方式）

这个目录让 CI 不配任何 GitHub Secrets 也能签出正式包。

## 只需要做一件事

打开 `keystore.properties`，把两行口令补上：

```properties
storeFile=wesdtf698.jks
storePassword=这里填你的库口令
keyAlias=wesdtf698
keyPassword=这里填你的私钥口令
```

保存、推送、Run workflow 选 `release`，完事。

`keyAlias=wesdtf698` 是从 jks 文件里读出来的实际值，已经填好，不用动。
JKS 里两个口令通常相同，不确定就填成一样的。

## 它会怎么被使用

`app/build.gradle.kts` 按这个顺序找签名，取第一个填全了的：

| 优先级 | 来源 | 用途 |
|---|---|---|
| 1 | `QINGSHUI_*` 环境变量 | CI 配了 Secrets 时 |
| 2 | `local.properties` | 本地 Android Studio |
| 3 | **`keystore/keystore.properties`** | **本目录，仓库内置** |

所以两种方式共存不冲突：配了 Secrets 就走 Secrets，没配就用这里的文件。
想让 CI 走 Secrets，把这个目录删掉推上去即可，workflow 会自动切换来源并在日志里写明用的是哪条路。

## 代价（自己权衡）

- `wesdtf698.jks` 和口令会进 git 历史。**推到公开仓库 = 签名彻底泄露**，别人能签出与你完全同签名的 APK，覆盖升级你的用户。
- 已经提交后想删掉，光 `git rm` 没用，历史里还在，得 force push 重写历史。
- 私有仓库 + 只有你一个人用，风险可控，这也是最常见的自用做法。

## 构建端会拦的错误

`Resolve signing keystore` 步骤在编译前就检查，不会浪费几分钟构建时间：

```
口令没填   → ::error::storePassword / keyPassword 还没填
alias 错   → ::error::你填的是 'xxx'，文件里的实际 alias 是 'wesdtf698'
文件损坏   → ::error::不是合法 keystore，头部字节=68656c6c
```

签名真正完成后还有一道 `Verify release signature`，用 apksigner 比对证书 SHA-256 指纹，
不是 `2D:57:C2:6A:...:20:14` 就直接判定构建失败。
