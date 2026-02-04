# FZMahjong 后端 | Backend

Spring Boot + WebSocket (STOMP) 的福州麻将游戏服务。  
Fuzhou Mahjong game server: Spring Boot + WebSocket (STOMP).

---

## 要求 | Requirements

- Java 21
- Maven 3.x

## 运行 | Run

在项目根目录执行 | From repo root:

```bash
./start.sh
```

或在本目录执行 | Or from this directory:

```bash
mvn spring-boot:run
```

服务默认端口：`8080`。静态资源（含前端构建产物）从 `src/main/resources/static` 提供。  
Default port: `8080`. Static assets (including frontend build) are served from `src/main/resources/static`.

## 结构 | Structure

- `src/main/java/com/fzmahjong/` — 游戏逻辑、API、WebSocket | Game logic, API, WebSocket
- `src/main/resources/static/` — 前端构建输出目录（由 `frontend` 的 `npm run build` 写入）| Frontend build output (written by `npm run build` in `frontend`)
