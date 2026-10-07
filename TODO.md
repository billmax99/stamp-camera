# TODO · 邮票相机

## 当前状态

- 网页版功能完备:三屏流程(拍照裁切成票/邮册)、5 套模板、4 套整体风格、
  全维度定制(齿孔·外形·内窗·纸面·背景·邮戳·盖戳力度·磨损·文字与票体拖转)、
  纸品(明信片/首日封/拍立得/四连整版)、美颜·滤镜(电影感/蓝调/暖调)·
  真人像虚化·贴饰跟脸·手电筒·九宫格构图线·显影动画·定时·分享、
  以吻封笺唇形票 + 拍嘴唇做专属吻章(色度抠图)
- 真机点击对焦:手动 focusDistance 扫描+单向推进(vivo 的 single-shot 是假动作)
- Android:`邮票相机-debug.apk`(12.7MB)debug 签名,已在 vivo 真机端到端验证;
  MediaPipe(wasm+人脸/分割模型)打包在 vendor/ 经 www 进 APK,加载失败自动降级
- 工作流:AGENTS.md / WRAP.md / TODO.md 就位(本文件),双电脑同步

## 已完成

- [x] 风格模板 8 套:新增 🥂 熟女时代/🌑 暗黑夜曲/🌹 摩登玫瑰
      (模板可带 beauty/markInk/ink 字段随套生效;纸面+3 背景+2;
      深色纸面浅色印字 inkColor 机制)
- [x] 饰品 20 个:14 跟脸 emoji(含礼帽/钻坠/骷髅贴/蝙蝠/弯月)+
      毛绒耳包/发箍(468点耳廓额顶 canvas 手绘)+ 4 固定
- [x] 五官级妆效:FaceLandmarker 468 点;腮红/口红/暗黑唇/眼影
      (唇类共用唇形路径、色随表行);贴饰随头倾斜旋转
- [x] 幽冥戳(☠️ 骷髅+日期+UNDERWORLD);暗黑夜曲配冥府邮局/拾叁面值
- [x] 自选票形:选图(透明PNG/黑白剪影自动转)当邮票外形,齿孔沿形过滤
- [x] 应用图标:邮戳红底+奶油齿孔邮票+红相机(矢量设计,CDP 确定性渲染
      15 个 mipmap 尺寸,自适应背景色同步),真机应用信息页验证
- [x] release 签名 APK:stamp-release.keystore(密码在 local.md,勿丢勿提交),
      build.gradle 检测 keystore.properties 自动签名、缺文件回退 debug;
      真机验证:覆盖安装、相机、点击对焦、出票全链路正常
- [x] 真人像虚化:MediaPipe selfie_segmenter 打包进 APK 替换伪景深
      (面积护栏 2%~92% 之外回退伪景深,拍静物不出鬼图)
- [x] 贴饰跟脸:BlazeFace 人脸检测(取景实时 overlay + 成片烙印),
      STICKERS 表 fx/fy/fs 锚点,无脸回退固定坐标
- [x] 借鉴同类 app 四件套:手电筒补光(torch)/ 复古色调滤镜 / 九宫格构图线 /
      拍立得显影动画(调研:Stampo/Stamp 邮票相机/NOMO/Dazz)
- [x] 开屏"播放按钮"修复(video 首帧前隐藏 + 后台回来自动重启相机)
- [x] 取景邮票框(随外形/内窗设置实时变化)
- [x] 真机点击对焦(单向推进,1.2~1.5s)
- [x] 吻痕精修:抠图后画笔编辑器(擦除/还原两笔刷,"还原"回到刚抠好状态)
- [x] 邮册整理:多选(✓角标)/全选/批量删除,选中邮票拼成"整版票"分享;
      邮票屏加"贴纸"一键导出透明 PNG(手账向)
- [x] 寄语(手写涂鸦笔,调研出的流行缺口):邮票屏 ✍️ 手指当笔在票面上
      写字画画,4 笔色(DOODLE_COLORS 表)+粗细+撤销/清空,笔迹烙进票面
      (印刷与邮戳之下,先写字后盖戳),随存册/下载/贴纸/分享全带笔迹

## 待办

- [中] iOS 版(需 Mac + Xcode + 开发者账号,`npx cap add ios`)

## 已知坑点

- 构建必须用 JDK21(`jdk21/jdk-21.0.2`,不进仓库;详见 打包说明.md),
  系统默认 JDK17 跑不动 Capacitor 7.6.9、JDK25 跑不动 Gradle 8.11
- 打包同步要带模型:`cp -r vendor www/`(MediaPipe wasm+模型,见 打包说明.md)
- WebView 的 MediaPipe **GPU delegate 必报 graph 错**(VIDEO 模式),
  FaceDetector/ImageSegmenter 都要固定 CPU delegate(真机实测)
- selfie 分割模型对"无人场景"(拍静物)输出不可信(桌面帧全 1),
  已加人像面积护栏兜底;虚化效果请拿真人验收
- 项目路径含中文:`android/gradle.properties` 已加 `android.overridePathCheck=true`
- vivo 安装 adb 包会弹"安全守护"确认,需勾选+继续安装(可自动化点击)
- vivo 相机服务偶发挂死(getUserMedia 60s 无响应),force-stop 重开即恢复
- Android WebView 只暴露后摄:翻转镜头在 APK 内天然不可用(已有 toast 降级)
- 定制抽屉加新选项的套路:往 CORE 区对应表加一行 → buildChips 自动出芯片
  → test.js 的表断言自动校验;改 drawPrint 后必跑 `node test.js`(齿孔安全区断言)
