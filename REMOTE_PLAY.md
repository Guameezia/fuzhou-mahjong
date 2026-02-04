# 异地联机说明 | Remote Play (不同网络与朋友联机)

你和朋友不在同一 WiFi 时，只要**其中一人**把本机游戏暴露到公网，其他人用浏览器打开链接即可一起玩。无需改代码、无需服务器，适合偶尔和好友联机。

---

## 思路简述

- **主机**：在你电脑上运行游戏后端，并用「内网穿透」把本机的 `http://localhost:8080` 暴露成一个**公网可访问的网址**。
- **其他人**：在浏览器里打开你分享的网址，即可加入同一房间一起玩。
- 前端使用相对路径（`/api`、`/ws-mahjong`），所以朋友打开你的公网链接时，请求会发到同一台后端，**无需任何配置或改代码**。

---

## 方式一：Cloudflare Tunnel（推荐，免登录）

**主机**需要：

1. 安装 [cloudflared](https://developers.cloudflare.com/cloudflare-one/connections/connect-apps/install-and-setup/installation/)  
   - macOS: `brew install cloudflared`
2. 先启动游戏后端（在项目根目录）：
   ```bash
   ./start.sh
   ```
   等控制台出现 “Started MahjongApplication” 后，**新开一个终端**，执行：
   ```bash
   cloudflared tunnel --url http://localhost:8080 --protocol http2
   ```
3. 终端里会打印一行类似：
   ```text
   https://随机名.trycloudflare.com
   ```
   把这条 **https 链接** 发给朋友。

**朋友**：用浏览器打开该链接即可，创建房间或输入房间号加入即可联机。

---

## 方式二：ngrok

1. 安装并注册 [ngrok](https://ngrok.com/)（免费账号即可）。
2. 启动游戏：`./start.sh`。
3. 新开终端执行：
   ```bash
   ngrok http 8080
   ```
4. 在 ngrok 输出的界面里找到 `Forwarding` 的 **https** 地址（如 `https://xxxx.ngrok-free.app`），发给朋友。

**朋友**：浏览器打开该链接即可。

---

## 方式三：Localtunnel（无需安装，有 Node 即可）

1. 启动游戏：`./start.sh`。
2. 新开终端执行：
   ```bash
   npx localtunnel --port 8080
   ```
3. 会给出一个 `https://xxx.loca.lt` 的链接，发给朋友。

**注意**：首次打开 localltunnel 的链接时，可能会有一个“输入 IP”的验证页，按页面提示操作即可。

---

## 方式四：路由器端口转发（固定公网 IP 时）

若你有公网 IP 且会配置路由器：

1. 在路由器里把 **TCP 8080** 端口转发到你运行游戏的电脑内网 IP。
2. 启动游戏：`./start.sh`。
3. 在 [https://ip.cn](https://ip.cn) 或搜索引擎查“本机公网 IP”，得到你的公网 IP（如 `123.45.67.89`）。
4. 把 **http://你的公网IP:8080** 发给朋友（注意：很多家庭宽带 80/443 被封，用 8080 一般可以）。

**朋友**：浏览器打开该地址即可。

---

## 使用脚本一键尝试远程暴露（可选）

项目根目录提供了 `start-remote.sh`，会先检查本机后端是否已运行，然后尝试用 **cloudflared** 暴露到公网（若已安装）。用法：

```bash
./start-remote.sh
```

按脚本提示把打印出来的公网 URL 发给朋友即可。若未安装 cloudflared，脚本会提示你使用上面任一方式手动暴露。

---

## 常见问题

- **朋友打开链接后白屏或连不上**  
  检查：主机上的 `./start.sh` 是否在运行；隧道/ngrok/端口转发是否在运行；链接是否带 `https://` 或 `http://`。

- **WebSocket 断开**  
  隧道或网络不稳定时会断线，刷新页面重新加入房间即可。

- **cloudflared 报错 `Failed to dial a quic connection` / `timeout: no recent network activity`**  
  这是网络环境不通 QUIC（UDP）导致的，改用 HTTP/2（TCP）即可：
  ```bash
  cloudflared tunnel --url http://localhost:8080 --protocol http2
  ```
  若你用的是脚本 `./start-remote.sh`，它默认就会使用 `http2`；也可以手动切回（不推荐）：
  ```bash
  CLOUDFLARED_PROTOCOL=quic ./start-remote.sh
  ```

- **只想局域网联机**  
  不运行隧道，直接让朋友在浏览器访问 **http://你的局域网IP:8080**（如 `http://192.168.1.100:8080`）。在终端执行 `ifconfig` 或 `ipconfig` 可看到本机局域网 IP。

---

**总结**：自己与朋友异地联机时，任选一种方式让「主机」的 8080 端口被公网访问即可；推荐先用 **Cloudflare Tunnel**，免登录、一条命令即可用。
