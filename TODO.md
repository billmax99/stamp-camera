# TODO · 邮票相机

## 当前状态

- 网页版功能完备:三屏流程(拍照裁切成票/邮册)、5 套模板、4 套整体风格、
  全维度定制(齿孔·外形·内窗·纸面·背景·邮戳·盖戳力度·磨损·文字与票体拖转)、
  纸品(明信片/首日封/拍立得/四连整版)、真美颜(磨皮美白 4 档+瘦脸滑杆)·
  滤镜(电影感/蓝调/暖调)·
  真人像虚化·贴饰跟脸·手电筒·九宫格构图线·显影动画·定时·分享、
  以吻封笺唇形票 + 拍嘴唇做专属吻章(色度抠图)
- 真机点击对焦 v5·原生通道:APK 内相机层整体换成原生 Camera2 插件(StampCam.java,
  Java 写、零新依赖),点按对焦在原生层近→远扫档选峰(与 v4 同算法:稳定判据/提前
  退出/对数抛物线,但没有 WebView 约束协商开销);预览走 127.0.0.1 MJPEG 帧流
  →canvas→captureStream 回喂原 <video>,美颜/人脸/裁切/点击映射零改动;拍照
  直取全幅 JPEG(4096 档),前后摄都可用(WebView 时代只暴露后摄)。
  onyx 实锤:AF_TRIGGER+区域在这台红米也是假动作(2s 镜头纹丝不动),唯一听管的
  是 manual LENS_FOCUS_DISTANCE;网页/iOS 自动回退 getUserMedia+v4 老路
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
- [x] 真美颜三件套:磨皮美白(SKIN 表 4 档,YCbCr 肤色掩码+低频层融合
      +sobel 边缘保护,非肤色不磨)+ 瘦脸滑杆(468 点咬肌双圆后向映射,
      五官零位移)+ 导入照片静态检测;顺手修了 idOf 严格匹配导致手选
      滤镜/虚化档从未生效的旧 bug(beauty/blurBg/skin 的 id 统一字符串)
- [x] 取景器 WebGL 实时美颜预览:GPU 片元着色器(16 采样近似磨皮+同款
      肤色掩码/边缘保护+瘦脸位移,与 CPU 成片同公式),瘦脸圆心每帧读
      lastFaceBox;开启美颜时 video 藏起由 warpCv 显示,色调滤镜同步;
      无 WebGL/关美颜/非相机屏自动退回原始画面;成片仍走 CPU 全分辨率
- [x] 真机反馈 8 项修复:取景窗独立容器留边(#vfBox,顶/底栏不再叠画面);
      虚拟键/键盘安全区(env+adjustResize);邮戳长按 550ms 进抽屉定制节
      (单击换样式/拖动不变);抽屉输入框键盘弹起自动滚进可视区;
      下载真机可用(MainActivity DownloadListener 接 data:URL 存
      Pictures/邮票相机,API29+ MediaStore);分享兜底改存相册(弃图片
      剪贴板假成功);存册 toast 澄清应用内邮册语义;返回键回上一屏
      (pushState/popstate 栈,相机屏无历史时仍可正常退出)
- [x] 点击对焦 v5·原生通道(用户定稿"像墨影那样,不管代价"):StampCam.java
      Camera2 引擎(open/tapFocus 扫描/capture 全幅/torch/setManual·getState 调试),
      MainActivity super.onCreate 前注册插件,Manifest 加 cleartext(loopback MJPEG);
      踩坑记:①Capacitor 7 插件必须 super.onCreate 前 registerPlugin 否则进不了桥
      ②loopback MJPEG 必须带 Access-Control-Allow-Origin: *(https://localhost 页面
      跨 origin fetch,CORS 缺头=Failed to fetch,曾据此误判成网络层问题)
      ③帧泵失败重试要有退避,否则每 500ms 关重开相机会把 HAL 搅死(CAMERA_DISCONNECTED)
      ④onyx 的 AF_TRIGGER+区域假动作,manual 屈光度是唯一真通道;CaptureResult 的
      LENS_FOCUS_DISTANCE 回报有时滞后数秒,别当实时真值
      真机验证:点近物从无穷远拉回到正确距离(清晰度 2.1 倍)、torch、前摄镜像、
      全幅拍照出票 1.5s、录屏对照预览正立无坏色;帧流 ~23.5fps 960 档
- [x] 下载/分享改透明纯票(用户定稿):齿孔打穿孔外全透明,像真邮票;
      原"贴纸"按钮功能重复已删;桌布版只留邮册存档(composeStoreJpeg)。
      真机验证:下载 PNG 边缘 alpha 呈齿/孔交替,票面 255 四角 0

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
