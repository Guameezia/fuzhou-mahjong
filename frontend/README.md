# FZMahjong 前端 | Frontend (React)

基于 React + TypeScript + Vite 的福州麻将前端。  
Fuzhou Mahjong UI: React + TypeScript + Vite.

---

## 开发 | Development

```bash
npm install
npm run dev
```

开发时需同时启动后端（默认 `http://localhost:8080`），Vite 会代理 `/api`、`/ws-mahjong`、`/images` 到后端。  
Start the backend (default `http://localhost:8080`) as well; Vite proxies `/api`, `/ws-mahjong`, and `/images` to it.

## 构建 | Build

```bash
npm run build
```

构建产物会输出到 `../backend/src/main/resources/static`，与 Spring Boot 静态资源目录一致。构建不会清空该目录（会保留 `images/` 等已有文件）。  
Output goes to `../backend/src/main/resources/static`. The directory is not emptied (e.g. `images/` is kept).

## 结构说明 | Structure

- **样式 Styles**：`src/styles/` 下按功能拆分（global、layout、table、tiles、actionBar、dialogs、mobile）。 Split by concern under `src/styles/`.
- **操作栏 Action bar**：吃/碰/杠/胡为窄长条悬浮条（`action-bar-float`），位于手牌上方。 Compact floating bar above hand tiles.
- **手机端 Mobile**：`src/styles/mobile.css` 中媒体查询调整牌桌与手牌比例。 Media queries in `mobile.css` for table and tile scaling.

旧版单页 `app.js` / `tile-images.js` 在首次使用 React 构建后仍会留在 `static/` 下，可手动删除。  
Legacy `app.js` / `tile-images.js` may remain in `static/` after the first React build; you can remove them manually.
