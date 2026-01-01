[![Java](https://img.shields.io/badge/Java-21+-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![Paper](https://img.shields.io/badge/Paper-1.21+-lightgrey.svg)](https://papermc.io/)
[![Folia](https://img.shields.io/badge/Folia-Supported-green.svg)](https://papermc.io/)

支持Paper/Folia服务器设计的 TXT 文本转成书物品插件。

> **重构版本**：现已完全支持 **Folia** 多线程调度，引入 **Modern Mode (现代模式)** 以支持富文本排版。

---

## ✨ 特性

### 🚀 极致性能与兼容
*   **Folia & Paper 完美支持**：使用现代 Scheduler API，在 Folia 的多线程环境下运行流畅，不打断主线程 TPS。
*   **异步加载**：文件读取与分页计算全部在异步线程完成，大文件生成书籍时玩家无卡顿感。
*   **NIO.2 文件处理**：优化的文件流处理，支持超大文本文件。

### 🎨 双模式渲染引擎

#### 1. Modern Mode (现代模式) - *推荐*
专为排版设计，采用 Paper 官方 `MiniMessage` 格式。
*   **RGB 渐变色**：支持 `<gradient:#red:#blue>文字</gradient>`。
*   **Hex 颜色**：支持 `<#RRGGBB>` 或 `<color:#RRGGBB>`。
*   **全格式支持**：加粗、斜体、下划线、删除线等所有标准格式。
*   **兼容性强**：依然识别传统的 `&颜色` 代码和 `&#RRGGBB`。

#### 2. Classic Mode (经典模式)
* **智能分页**：Smart Split 策略，自动识别空格和换行符进行断页。
* **多种策略**：支持 `smart`(智能), `lines`(按行), `hard`(强制), `marker`(标记符) 四种分页方式。
* **稳定兼容**：完美支持传统的 `&` 颜色代码。

### 🌍 国际化 (i18n)
内置中/英文语言包，支持一键切换，无硬编码提示。支持自定义语言文件。

---

## 📦 安装

1. 从 [Releases](../../releases) 下载最新版本的 `.jar` 文件。
2. 将其放入服务器的 `/plugins` 文件夹。
3. **重启服务器** (或使用插件管理器热加载)。
4. 配置文件 `config.yml` 和语言文件将自动生成。

---

## ⚙️ 配置

在生成的 `config.yml` 中设置你的偏好：

```yaml
# 语言设置: "zh_CN" 或 "en_US"
language: "zh_CN"

# 核心模式切换: "classic" 或 "modern"
Switch-mode: "modern"

# Modern 模式配置
modern:
  # 自动去除首尾空白字符
  trim_whitespace: false
```

### 文本文件放置

将你的 `.txt` 文件放入插件文件夹中（默认位于 `/plugins/BookPrinter/`）。

---

## 📖 使用指南

### 指令

| 指令 | 权限 | 说明 |
| :--- | :--- | :--- |
| `/bookprinter <文件名> [署名]` | `bookprinter.use` | 生成书籍。 |
| `/bookprinter reload` | `bookprinter.reload` | 重载配置和语言文件。 |
| `/bookprinter info` | `bookprinter.info` | 查看插件运行模式和状态。 |

### 现代模式 写作示例

假设我们有一个 `story.txt` 文件，内容如下：

```text
&l第一章：冒险的开始

这是一本关于 &l&6冒险&r 的书。

&n注意：&r本书支持 RGB 渐变色！
这就是 <gradient:#FF5555:#5555FF>彩虹渐变</gradient> 的效果。

(如果你想换页...)
就使用 \Line-break\ 符号！
这里是第二页的内容。
\n
(这里手动敲了个回车并使用 \n 符号，也可以直接换行)
```

### 经典模式 (Classic Mode) 写作示例

如果你更习惯传统方式，可以在 `config.yml` 中将模式改为 `classic`，并使用以下特性：

```text
第一页的内容，&e黄色文字&f。
---PAGE---
这里强制插入了一个分页符，内容会进入下一页。
如果开启了 classic.preserve_newlines，这里的空行也会被保留。
```
## 📜 许可证

本项目采用 [LGPL-3.0](LICENSE) 许可证。
```