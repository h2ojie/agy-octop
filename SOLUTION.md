# Octop Android 客户端与全链路远程接入整体架构方案 (v1.0.30)

> **方案版本**：v1.0.30  
> **更新时间**：2026-10-16  
> **当前状态**：全链路生产就绪，已全面落地 Antigravity 交互范式、会话级模型选择与格式化、吸顶置顶、滚动手势隔离、流式排队队列与多渠道自动分发  

---

## 一、 系统架构总览

本方案旨在为个人部署在本地 Mac 的 Octop 私有大模型 Agent 矩阵（包含 8 大垂直领域专家），构建一条**安全、极速、无缝穿透局域网**的 Android 移动端原生接入通道。手机端无需安装 VPN 或 Tailscale 客户端，即可随时随地以原生交互体验与专家进行流式对话、多模态拍照分析、排队发消息与多会话管理。

### 1.1 全链路拓扑图

```mermaid
flowchart TD
    subgraph MobileDevice ["移动端 (Android App - Antigravity 范式)"]
        UI["Jetpack Compose UI (极简高信息密度 / 轮次吸顶)"]
        WS["OkHttp WebSocket Client (专线保活池)"]
        HTTP["Retrofit REST Client (多模态上传 / 审批 / 历史)"]
    end

    subgraph Internet ["公网传输 (TLS 1.3 / WSS)"]
        Domain["h2ojie2026.duckdns.org:8443"]
    end

    subgraph RelayServer ["公网中转节点 (新加坡 Lightsail 47.130.249.193)"]
        Nginx["Nginx 1.24 (SSL Offloading & AppKey Filter & APK Hosting)"]
        TailscaleNodeA["Tailscale Node (100.103.230.122)"]
    end

    subgraph MacHost ["本地工作站 (Mac 本地局域网)"]
        TailscaleNodeB["Tailscale Node (100.106.164.26)"]
        OctopBackend["Octop FastAPI Backend (:8088)"]
        AgentRuntime["8 大领域专家 Runtime (LangGraph + Checkpointer)"]
    end

    UI --> WS
    UI --> HTTP
    WS -->|WSS / 加密流式双向通信| Domain
    HTTP -->|HTTPS / REST API| Domain
    Domain --> Nginx
    Nginx -->|X-Octop-App-Key 校验通过| TailscaleNodeA
    TailscaleNodeA == WireGuard 虚拟内网 == TailscaleNodeB
    TailscaleNodeB --> OctopBackend
    OctopBackend --> AgentRuntime
```

---

## 二、 核心网络与安全防护体系

由于将家庭/个人本地服务开放至外网存在公网爬虫探测与恶意暴力破解风险，本方案构建了**四重零信任纵深防御**：

### 2.1 四重安全防线

1. **第一道：全链路 TLS 1.3 证书加密（防嗅探）**
   - 采用 DuckDNS 动态域名 `h2ojie2026.duckdns.org` 配合 Let's Encrypt 权威 CA 证书。
   - 杜绝自签名证书在 Android 9+ 系统下的握手阻断与安全弹窗风险。
2. **第二道：静态签名头静默丢弃（防扫描器探测）**
   - 移动端所有 HTTP/WebSocket 请求统一注入自定义私密 Header：`X-Octop-App-Key: <UUID_SECRET>`。
   - 中转机 Nginx 在处理请求前首先校验该 Header，若不匹配直接返回 `444`（立即强行关闭 TCP 连接，不返回任何响应数据），使扫描器判定端口为 Filtered。
3. **第三道：Tailscale 虚拟内网加密传输（防内网暴露）**
   - 中转机与 Mac 本地工作站之间基于 WireGuard 底层协议打通点对点虚拟网络。
   - Mac 本身无需配置公网 IPv4 或路由器端口映射（NAT 穿透），彻底隐藏物理真实 IP。
4. **第四道：JWT 动态会话鉴权（防越权操作）**
   - REST 接口与 WebSocket 连接必须携带 `Authorization: Bearer <JWT_TOKEN>`。
   - 令牌具备有效生命周期与用户级数据隔离。

---

## 三、 服务端与反向代理标准配置

中转服务器（新加坡 Lightsail `47.130.249.193`）部署加固版 Nginx 配置（`/etc/nginx/conf.d/octop.conf`），解决流式打字机卡顿、图片上传被截断与超时断连问题：

```nginx
# /etc/nginx/conf.d/octop.conf

upstream octop_mac {
    server 100.106.164.26:8088 max_fails=2 fail_timeout=5s;
    keepalive 32;
}

map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

server {
    listen 80;
    server_name h2ojie2026.duckdns.org;
    
    # APK 快速分发下载路径
    location = /octop.apk {
        alias /var/www/html/octop.apk;
        default_type application/vnd.android.package-archive;
        add_header Content-Disposition 'attachment; filename="octop.apk"';
    }

    # 其余 HTTP 流量重定向至 HTTPS
    location / {
        return 301 https://$host:8443$request_uri;
    }
}

server {
    listen 8443 ssl http2;
    server_name h2ojie2026.duckdns.org;

    ssl_certificate     /etc/letsencrypt/live/h2ojie2026.duckdns.org/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/h2ojie2026.duckdns.org/privkey.pem;

    # 1. 放开图片与附件上传体积至 50M
    client_max_body_size 50m;

    # 2. 阻断非专属客户端请求（扫描器直接丢弃连接）
    if ($http_x_octop_app_key != "YOUR_SECURE_UUID_APP_KEY") {
        return 444;
    }

    access_log /var/log/nginx/octop_access.log;

    location / {
        proxy_pass http://octop_mac;
        proxy_http_version 1.1;

        # WebSocket 协议升级支持
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # 3. 彻底关闭代理缓冲，保证 AI 打字机流式输出零延迟
        proxy_buffering off;
        proxy_cache off;
        chunked_transfer_encoding on;

        # 4. 连接超时控制：Mac 离线时 3 秒快速熔断，深度推理保持长连接 600 秒
        proxy_connect_timeout 3s;
        proxy_read_timeout 600s;
        proxy_send_timeout 600s;
    }
}
```

---

## 四、 移动端 Antigravity 交互范式架构 (v1.0.25)

客户端采用 **原生极客方案（Kotlin + Jetpack Compose + OkHttp + Retrofit）**，严格对标 Google Antigravity Web 移动端的现代化交互体验：

### 4.1 交互轮次重组与吸顶机制 (Sticky Header)
- **对话轮次聚合 (`groupMessagesIntoTurns`)**：打破平铺散乱的聊天气泡，按用户提问将 AI 思考、工具调用统计与最终答复组织为紧凑轮次。
- **发送瞬时 85% 视口缓冲 (`snapTo`)**：发送新提问瞬间提供充足滑动下潜空间，配合微延迟布局稳定，100% 精确置顶在屏幕最上方。
- **回复结束平滑收拢 (`animateTo`)**：AI 流式回复完成后，底部视口缓冲顺滑收缩至 16dp，彻底杜绝向上拉出大片黑边空白。

### 4.2 提问卡片智能收拢体系 (`activeExpandedTurnId`)
- **默认全折叠**：所有历史提问默认展示为 1 行极简条（单行文字 + 省略号 + 展开指示图标）。
- **滑动即刻收拢**：检测到列表滑动（`listState.isScrollInProgress`）时，立即自动将展开的提问卡片平滑收拢为一行。
- **点击非提问区域收拢**：点击 AI 答复、列表背景或空白区域即刻自动收回单行。
- **按需再展开**：点击单行卡片展开全文与小图标，点击“点击收拢”即可随时收回。

### 4.3 提问对话框内部滚动隔离 (Nested Scroll Isolation)
- **拦截器闭环 (`stopNestedScrollConnection`)**：在展开的提问卡片上挂载专用嵌套滚动拦截器，并在顶栏与外层容器捕获垂直滚动。
- **杜绝连带拉扯**：在提问框内部上下滑动浏览长提问时，位移在卡片内部完全消化，**向下拉绝不会拉出上一轮历史，向上滑绝不会把当前答案顶上来**。

### 4.4 流式回复中排队发消息 (In-Streaming Message Queue)
- **排队发送按钮**：当大模型正在思考或回复时，输入框检测到输入内容后，发送按钮自动演化为 Antigravity 标志性的 **蓝色圆底排队箭头**。
- **排队队列卡片**：消息发送后进入 `queuedMessages`，展示在输入框上方；当前轮次结束后，排队消息自动出队无缝触发下一轮。

### 4.5 56dp 微边框小图标缩略图
- **告别铺屏大图**：提问卡片内废弃 240dp 大图与“已上传附件:”繁复标题，采用精致的 **56dp × 56dp** 方形小图标缩略图。
- **横向平滑滑动**：多图水平排列滑动，点击任意小图标调起全屏查看器。折叠状态下展示 `📎 [N个附件]`。

---

## 五、 会话生命周期与跨专家安全路由

### 5.1 专家矩阵与双重路由安全锁
1. **专家切换长连接存活**：切换专家时不掐断后台 WebSocket，后台任务持续运行不丢包。
2. **双重可见性准入准则**：
   $$\text{IsVisible} = (\text{\_currentAgent.agentId} == \text{agentId}) \land (\text{\_currentThread.threadId} == \text{targetThreadId})$$
   未满足条件的消息静默写入专属会话缓存 `_threadMessagesCache`，绝不刷新当前主界面。
3. **权威历史一致性与折叠**：拉取历史时运行 `processDialogueHistory`，提取最终权威消息并累加全阶段思考链与工具调用统计。

---

## 六、 客户端编译与全渠道分发规范

### 6.1 编译环境与工具链
- **JDK 17**：`/Users/konglingling/.jdks/corretto-17/Contents/Home`
- **Android SDK**：`/Users/konglingling/Library/Android/sdk` (Target SDK 34)
- **当前版本**：**v1.0.31** (`versionCode = 32`)

### 6.2 自动化分发矩阵
编译完成后，安装包与源码包会自动同步至以下分发节点：
1. **本地工程目录**：`/Users/konglingling/agy-octop/octop-v1.0.32.apk`
2. **局域网共享工具盘**：
   - 最新版本：`/Volumes/tools/apk/octop-v1.0.32.apk`
   - 兼容快捷路径：`/Volumes/tools/apk/octop-v1.0.0.apk`
3. **Google Drive 云端硬盘 (`octop-apk/`)**：
   - `octop-v1.0.32.apk`
   - `octop-v1.0.0.apk`
   - 完整工程源码包：`octop-android.zip`
4. **新加坡中转节点公网直链**：
   - [http://h2ojie2026.duckdns.org/octop.apk](http://h2ojie2026.duckdns.org/octop.apk)

---

## 七、 异常与 Issue 跟踪矩阵 (001 ~ 030)

在项目 `issue/` 目录下完整维护了迭代历程中的 30 项深度技术归档：

| 编号 | 文档名称 | 核心解决问题 |
| :--- | :--- | :--- |
| **001** | `001-toast-thread-notfound.md` | 修复初始化线程未就绪时的报错 Toast |
| **002** | `002-chat-bubble-raw-block-format.md` | 解包 `[{type=text,...}]` 原始 JSON 块格式 |
| **003** | `003-markdown-rendering-incomplete.md` | 补全流式 Markdown 标签与代码块语法解析 |
| **004** | `004-incomplete-dialogue-history-pagination-and-tool-filtering.md` | 历史消息分页与工具调用过滤清洗 |
| **005** | `005-status-bar-color-match-app-theme.md` | 沉浸式状态栏与主题配色融合 |
| **006** | `006-websocket-send-turn-stuck-in-thinking.md` | 修复长推理场景下流式结束看门狗挂起 |
| **007** | `007-multi-agent-switching-message-loss-and-blocking.md` | 多智能体长连接保活池与切专家防丢包 |
| **008** | `008-cross-agent-message-leakage-on-switch.md` | 跨专家消息串传双重安全锁设计 |
| **009** | `009-turn-message-consolidation-and-tool-summary.md` | 轮次折叠算法对齐与工具调用合并展示 |
| **010** | `010-image-and-file-upload-pipeline.md` | 多模态拍照/相册文件上传与缩略图流水线 |
| **011** | `011-thread-scoped-input-draft-and-keyboard-dismiss.md` | 会话级输入草稿持久化与键盘自动隐藏 |
| **012** | `012-markdown-table-column-alignment-and-grid-layout.md` | Markdown 表格等宽列对齐与自适应网格 |
| **013** | `013-antigravity-chat-layout-sticky-header-and-auto-expanding-input.md` | 落地 Antigravity 轮次吸顶卡片与自适应输入栏 |
| **014** | `014-sticky-prompt-card-scroll-and-concise-input-bar.md` | 提问卡片高度保护与输入栏精简化 |
| **015** | `015-natural-flow-prompt-cap-and-scroll-auto-retract.md` | 自然流提问高度限制与滚动收拢 |
| **016** | `016-precise-send-and-stop-buttons-antigravity-styling.md` | Antigravity 风格小巧发送与停止按钮 |
| **017** | `017-dock-new-turn-to-top-on-send.md` | 提问发送后瞬时平滑滚至最上方置顶 |
| **018** | `018-long-press-select-copy-cut.md` | 提问与输入框长按选中、复制、剪切全链路支持 |
| **019** | `019-antigravity-queued-messages.md` | 流式生成中排队发消息与队列可视化卡片 |
| **020** | `020-fix-scroll-rebound-and-preserve-full-docked-card.md` | 修复生成中滑动回弹与保留完整置顶状态 |
| **021** | `021-retract-bottom-spacer-on-stream-complete-and-dismiss-keyboard-on-send.md` | 回复完成后收缩底部缓冲并自动隐藏输入法 |
| **022** | `022-fix-send-top-docking-via-instant-headroom-snap.md` | 采用瞬时 85% 视口缓冲彻底保证置顶吸顶 |
| **023** | `023-auto-retract-user-card-on-touch-scroll-and-compact-thumbnails.md` | 点击列表/滑动 AI 回复自动收拢提问卡片为一行及 56dp 小图标 |
| **024** | `024-isolate-user-prompt-scroll-from-parent-list.md` | 提问对话框内部纵向滚动与外层列表彻底隔离 |
| **025** | `025-single-line-top-app-bar-with-marquee-title.md` | 标题与管家名称合并为单行显示，长标题跑马灯滚动 |
| **026** | `026-compact-44dp-top-bar-with-preserved-drawer-button.md` | 顶部栏压缩至 44dp 紧凑高度并保留抽屉汉堡按钮 |
| **027** | `027-ai-response-model-name-and-generation-timestamp.md` | AI 回复卡片底部展示模型名称与生成时间 (如 `Gemini 3.8 Flash · 10月16日 14:34`) |
| **028** | `028-model-and-reasoning-effort-selection-bottom-sheet.md` | 输入框底部模型切换与思考强度 (Reasoning Effort) 二级选择弹窗 |
| **029** | `029-fix-ai-response-model-name-resolution-and-formatting.md` | 修复 AI 回复卡片模型名称归属与格式化解析错误 (对齐 `GPT-6 Luna` 等实际调度模型) |
| **030** | `030-display-provider-name-cli-ice.md` | 模型服务商标识显式呈现与同名消歧 (`[cli]` / `[ice]` 徽章与输入胶囊/卡片联动) |
| **031** | `031-code-review-optimizations-and-repository-refactoring.md` | 深度 Code Review 采纳落地：动态代理单例缓存、401 自动失效拦截、HttpUrl 安全重构、数据仓库层 (OctopRepository) 与附件上传管线 (AttachmentUploadPipeline) 架构解耦，字号自适应及 Compose 渲染优化 |
