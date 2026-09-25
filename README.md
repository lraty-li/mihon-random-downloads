# Mihon Random Downloads

一个面向 Mihon 的本地下载随机浏览扩展。

它不会从网络搜索漫画，而是从 Mihon 已经下载到本机的漫画中随机抽取一屏（默认 20 本），可直接进入本地章节并使用 Mihon Reader 阅读。

> 当前版本：**1.6.15**
>
> 已在 **Mihon 0.20.4** 上验证。

## 功能

- 从完整本地下载池随机抽取 20 本漫画；
- “热门 / 最近更新”均可作为重新随机入口；
- 支持按漫画目录名或下载图源名搜索；
- 直接读取本地文件夹章节、CBZ 和 ZIP；
- 优先使用已有 `cover.jpg` 等本地封面；
- 从下载章节的 `ComicInfo.xml` 读取真实作者/画师；
- 不联网补全作者，不从标题猜作者；
- 不直接访问 Mihon SQLite；
- Random Downloads 自身运行代码不使用反射；
- 不创建插件索引文件，不修改、重命名、删除或解压下载内容。

## 工作方式

正常情况下，扩展只读 Mihon 已生成的 `cacheDir/dl_index_cache_v3`，解析其中已经索引的下载漫画。

为了避免遗漏 Mihon 当前没有识别到的下载图源目录，还会：

1. 只列一次真实 downloads 根目录；
2. 找出 Mihon 下载索引缺失的图源；
3. 只补扫这些图源的漫画目录；
4. 合并成完整的内存随机池。

当前测试设备：

```text
物理图源目录: 30
物理漫画目录: 2924
最终随机池: 2924

首次构建随机池: 约 0.3-0.4 秒
后续重新随机: 约 1-5 ms
```

如果 Mihon 的磁盘缓存缺失、损坏或升级为不兼容版本，扩展会退回一次性的只读 SAF 扫描。

## 作者信息

漫画详情仅在 Mihon 请求详情时读取作者，不影响随机页性能。

读取顺序：

1. 检查最近最多 3 个下载章节；
2. 从文件夹章节或 CBZ 内读取 `ComicInfo.xml`；
3. `Writer` → 作者；
4. `Penciller` → 画师；
5. 没有可靠元数据时保持“未知作者”。

不会从 `[作者]`、`[社团 (作者)]` 等标题格式推断作者。

## CBZ

CBZ 使用项目内的 `keiyoushi.zip` 公共 ZIP 解析代码：

- 解析 central directory；
- 通过只读 `ParcelFileDescriptor` 范围读取；
- 按需解压单个 entry；
- 不将 CBZ 解压到磁盘。

## 文件安全边界

Random Downloads 自身：

- 不直接打开 `tachiyomi.db`；
- 不使用 Android `SQLiteDatabase`；
- 不创建自己的磁盘索引/缓存/记录文件；
- 不创建、删除、重命名或修改下载文件；
- SAF / ContentResolver 仅做读取；
- 文件描述符只以 `"r"` 打开；
- 不通过反射访问 Mihon `DownloadCache`、`ArchiveReader` 等内部实现。

## 构建

需要 JDK 17+ 和 Android SDK。

Windows：

```powershell
.\gradlew.bat :src:all:randomdownloads:assembleDebug
```

Linux / macOS：

```bash
./gradlew :src:all:randomdownloads:assembleDebug
```

APK 输出：

```text
src/all/randomdownloads/build/outputs/apk/debug/
tachiyomi-all.randomdownloads-v1.6.15.apk
```

本机 Android SDK 路径应写在未提交的 `local.properties`：

```properties
sdk.dir=/path/to/android-sdk
```

## 项目结构

```text
src/all/randomdownloads/   Random Downloads 扩展实现
core/                      使用的 Keiyoushi 公共扩展基础代码
compiler/                  Keiyoushi KSP source processor
gradle/                    Gradle build logic / version catalog
common/                    扩展公共 Android manifest / ProGuard 配置
```

这个仓库只保留构建 Random Downloads 所需的 Keiyoushi 基础设施，不包含 Keiyoushi 的其他图源。

## 上游与许可

构建基础设施和部分公共代码来自 **Keiyoushi extensions-source**，详细来源见 [UPSTREAM.md](UPSTREAM.md)。

代码按 [Apache License 2.0](LICENSE) 分发。

本项目与 Mihon、Tachiyomi、Keiyoushi 及任何内容提供方均无官方关联。
