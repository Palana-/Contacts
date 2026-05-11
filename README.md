# 电话本

电话本是一个原生 Android 通讯录应用，使用 Kotlin 和 Android View 实现，不依赖 Flutter 或跨平台框架。

## 功能

- 应用名称：电话本
- 应用图标：本子形状图标
- 底部导航：通讯录、最近、更多
- 通讯录页：
  - 有头像的联系人用两列网格展示
  - 无头像的联系人用列表展示，每行显示姓名和手机号
- 联系人详情：
  - 点击联系人弹出详情弹窗
  - 弹窗支持返回按钮、点击空白处、系统返回键关闭
  - 弹窗内展示头像、姓名、号码、拨号、编辑、删除
- 拨号：
  - 点击拨号按钮直接发起电话呼叫
  - 首次拨号会请求电话权限
- 编辑：
  - 新增/编辑联系人
  - 支持从系统文件选择头像

## 构建

```powershell
cd android
.\gradlew.bat assembleDebug
```

Debug APK 输出位置：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```
