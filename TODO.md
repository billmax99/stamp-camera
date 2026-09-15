# TODO · 邮票相机

## 当前状态

- 网页版功能完备:三屏流程(拍照裁切成票/邮册)、5 套模板、4 套整体风格、
  全维度定制(齿孔·外形·内窗·纸面·背景·邮戳·盖戳力度·磨损·文字与票体拖转)、
  纸品(明信片/首日封/拍立得/四连整版)、美颜·虚化·贴饰·定时·分享、
  以吻封笺唇形票 + 拍嘴唇做专属吻章(色度抠图)
- Android:`邮票相机-debug.apk` debug 签名,已在 vivo 真机端到端验证
- 工作流:AGENTS.md / WRAP.md / TODO.md 就位(本文件),双电脑同步

## 待办

- [高] release 签名 APK(替换 debug 签名,才能长期分发)
- [高] 真人像虚化:打包 MediaPipe/分割模型进 APK,替换伪景深(接口在 processPhoto)
- [中] iOS 版(需 Mac + Xcode + 开发者账号,`npx cap add ios`)
- [中] 吻痕抠图精修:画笔蒙版手动修边(现阈值法,素唇效果一般)
- [低] 邮册导出/多选分享;模板自定义配色

## 已知坑点

- 构建必须用 JDK21(`jdk21/jdk-21.0.2`,不进仓库;详见 打包说明.md),
  系统默认 JDK17 跑不动 Capacitor 7.6.9、JDK25 跑不动 Gradle 8.11
- 项目路径含中文:`android/gradle.properties` 已加 `android.overridePathCheck=true`
- vivo 安装 adb 包会弹"安全守护"确认,需勾选+继续安装(可自动化点击)
- Android WebView 只暴露后摄:翻转镜头在 APK 内天然不可用(已有 toast 降级)
- 定制抽屉加新选项的套路:往 CORE 区对应表加一行 → buildChips 自动出芯片
  → test.js 的表断言自动校验;改 drawPrint 后必跑 `node test.js`(齿孔安全区断言)
