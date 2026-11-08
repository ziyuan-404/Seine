# REAR Widget API

- Seine 提供的背屏组件管理 API
- 通过此API您可以在您的应用/模块中操作小米妙想背屏的组件

## 协议

- REAR Widget API部分单独使用LGPLv3协议开源

## 依赖

在 Seine 源码仓库运行 `./gradlew :rear-widget-api:assembleRelease`，将 `rear-widget-api/build/outputs/aar/rear-widget-api-release.aar` 放入客户端项目的 `libs/`。

```kotlin
dependencies {
    implementation(files("libs/rear-widget-api-release.aar"))
}
```

## 注册权限

```xml

<uses-permission android:name="fr.uwu.Seine.permission.ACCESS_REAR_WIDGET_API" />
```