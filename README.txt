本次只需替换 2 个文件，保持目录结构直接覆盖到仓库根目录：

  app/src/main/java/me/rerere/rikkahub/data/qqbot/QQBotSocketClient.kt
  .github/workflows/build-apk.yml

keystore/ 目录一个字节都不要动，你填好的口令在里面。

QQBotSocketClient.kt 的两处修复：
  209 行  val raw = json["message"] ?: return null     （原来 null 会落进 when 的 else，里面 raw.jsonArray 就是可空调用）
  223 行  val name = ...stringish("name").orEmpty()    （原来少了 orEmpty）

build-apk.yml 的修复：
  新增 Report compile errors 步骤，并把提取正则从只认 file:/// 改成兼容 Gradle 9 的相对路径，
  以后编译失败时 Actions 页面直接标出文件名和行号。
