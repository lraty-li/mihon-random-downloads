# Mihon Random Downloads

个人 Mihon 图源扩展：从本机已经下载的漫画中随机显示 20 本，并可直接进入本地章节和 Reader。

## 当前架构

```text
浏览 -> 图源 -> Random Downloads
     -> 从完整下载池随机 20 本
     -> 漫画详情
     -> 本地章节
     -> Reader
```

Random Downloads 使用独立的 Mihon 图源身份，因此下载角标、阅读进度、原图源身份不保证与原条目一致。

## 随机池

正常路径：

1. 只读 Mihon 私有缓存目录中的最新兼容 `dl_index_cache_v3`。
2. 用 Kotlinx ProtoBuf 解码 Mihon 已索引的下载漫画。
3. 只列一次真实 downloads 根目录的图源目录。
4. 对 Mihon 索引遗漏的图源目录做补充读取。
5. 合并成完整内存随机池。
6. 后续“热门 / 最近更新”切换只在内存中重新抽 20 本。

当前设备实测：

```text
物理图源目录: 30
物理漫画目录: 2924
Mihon v3 索引漫画: 2854
补充图源: 10
补充漫画: 70
最终随机池: 2924

首次构建完整随机池: ~421 ms
后续重新随机: 1-2 ms
```

如果 Mihon 将缓存升级到不兼容版本、缓存不存在或首次解码失败，扩展会退到一次性的只读 SAF 全量兜底。已有可用内存 snapshot 时优先继续使用，不因瞬时缓存读取失败清空随机池。

## 无反射

Random Downloads 直接继承 `HttpSource`，不经过带反射初始化的 `KeiSource`。

扩展运行路径不使用：

- `Class.forName`
- `getDeclaredField`
- `getMethod`
- `isAccessible`
- 反射 `invoke`
- Mihon 内部 `DownloadCache` 对象反射
- Mihon 内部 `ArchiveReader` 反射

## 文件安全边界

扩展本身：

- 不直接打开 Mihon SQLite 数据库；
- 不创建插件索引文件；
- 不创建/删除/重命名/修改下载文件；
- 不把 CBZ 解压到磁盘；
- 只读 Mihon 已有 `dl_index_cache_v3`；
- SAF / ContentResolver 仅进行读取；
- `ParcelFileDescriptor` 仅以 `"r"` 打开。

## SAF 使用

随机主链不再通过 SAF 枚举全部漫画。

SAF 仅用于：

- 列 downloads 根目录以发现 Mihon 索引遗漏的图源；
- 补读这些遗漏图源的漫画目录；
- 打开具体漫画时读取章节；
- 打开具体图片/CBZ。

所有 child URI 都基于传入的 tree/document URI 构造，不再读取 Mihon 私有 `storage_dir` SharedPreferences。

## 搜索

搜索直接查询完整随机池，不再只搜索“曾经随机出现过”的漫画。

支持按：

- 漫画目录名
- 下载图源目录名

匹配。

## 章节

对 Mihon 已索引漫画：

- 普通章节目录必须存在于 Mihon 下载索引；
- CBZ 名称必须存在于 Mihon 下载索引；
- `_temp` / `.tmp` / 隐藏目录会被排除；
- 手工 `.zip` 章节继续保留兼容。

对 Mihon 未索引、由补充扫描发现的漫画，接受非临时章节目录、CBZ 和 ZIP。

## 封面

优先直接尝试：

```text
cover.jpg
cover.jpeg
cover.png
cover.webp
cover.avif
```

正常漫画不会先执行 `listChildren()`；当前设备绝大多数漫画都能直接命中 `cover.jpg`。

只有没有现成 cover 文件时，才枚举该漫画目录并回退到第一章第一张图片。

## CBZ

CBZ 使用扩展框架公开的 `keiyoushi.zip`：

- 解析 ZIP central directory；
- 通过只读 `ParcelFileDescriptor + FileChannel` 做范围读取；
- 按需解压单个 entry；
- 不落盘。

真机已验证一个 CBZ 返回 10 页并可连续翻页。

## 构建

```powershell
.\gradlew.bat :src:all:randomdownloads:assembleDebug
```

当前版本：

```text
1.6.12
src/all/randomdownloads/build/outputs/apk/debug/tachiyomi-all.randomdownloads-v1.6.12.apk
```
