# UI/UX 工作區改版

## 還原基準

改版前的 `main` commit 為 `84fb51b`。Tag `pre-ui-redesign-20261021` 已推送到 `origin` 與 `bt`，可作為回到改版前版本的依據。

## 設計依據

- [UI UX Pro Max](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill)：使用設計系統查詢、`data dense dashboard` 風格與鍵盤焦點建議。
- [Vercel Web Design Guidelines](https://github.com/vercel-labs/agent-skills/tree/main/skills/web-design-guidelines)：依其 Web Interface Guidelines 檢核導覽語意、表單標籤、焦點、載入回饋、內容溢出、動態效果與本地字型。
- 設計系統查詢中的行銷首頁 pattern 與本產品不符，不採用；採用符合企業工具的資料密集工作區、層級與可讀性建議。

## 視覺系統

以淺色工作區與石墨綠導覽列建立層級，綠色作為同步主動作。共用 tokens 定義於 `static/styles.css`。

| 用途 | 規格 |
| --- | --- |
| 頁面背景 | `#f5f7f6` |
| 導覽背景 | `#192c24` |
| 主要文字 | `#182e25` |
| 次要文字 | `#586b61` |
| 主動作 | `#16714d` |
| 字型 | 本地 IBM Plex Sans；中文使用系統中文字型 fallback |
| Hash、時間與程式碼 | 本地 JetBrains Mono、等寬數字 |
| 圖示 | 共用內嵌 SVG，裝飾圖示隱藏於輔助技術 |
| 動態 | 短時間 transform / opacity 回饋；支援 reduced motion |

字型與 OFL 授權一起放在 `static/fonts/`。部署時不需要 CDN、Internet、npm 或 Node.js。

## 導覽與資訊架構

主頁以 hash 保留可直接開啟的工作區：

- `/#projects`：專案同步，預設入口。
- `/#remotes`：Remote 模板管理。
- `/#activity`：最近載入的執行紀錄與審查資訊。
- `/#settings`：全局工作目錄、匯入與匯出。
- `/#certificates`：HTTPS 公開憑證、網域／URL 前綴與信任設定，並與工作區設定互設分頁連結。
- `/batch-version.html`：批次版本比對。
- `/diff.html?ruleId=...`：Commit 審查。

所有畫面共用 `static/ui.js` 的導覽、圖示、通知、日期格式與彈窗互動。

## 專案同步

1. 首頁優先呈現同步操作，工作目錄與 Remote 設定放在對應導覽。
2. 概況顯示真實 project 數量、rule 數量、有效自動排程數量與異常數量。
3. 點「需要處理」會顯示失敗與中斷規則。
4. 專案仍可摺疊，收合控制需有 accessible name 與 expanded 狀態。
5. 每條規則保留同步、版本比對、Force Push、自動同步；較少使用的驗證、差異、編輯與刪除放於「更多」。
   需要人工確認的規則優先顯示「檢視 Commit」，以引導使用者完成確認後再推送，版本比對則保留在「更多」。
6. 若有執行紀錄，提供該規則的查看紀錄操作。
7. 失敗結果仍顯示簡短 `Sync Error`，滑鼠與鍵盤焦點皆可讀完整訊息。
8. 自動刷新保持原有設定 dirty 保護；資料沒變時不重建專案表格，以保留鍵盤焦點與展開的選單。
9. 顯示後端服務連線狀態與最後更新時間；這不是 Git remote 可連線的驗證結果。

## 批次比對與 Commit 審查

批次比對保留選擇規格、勾選專案、結果篩選、重新比對原批次與單筆重試功能。輪詢更新結果時，不可重新建立專案 checkbox 或覆蓋使用者正在挑選的下一批專案。重新比對本批需沿用原 job 的 project IDs。

若舊 Java 服務回傳的規格缺少 `projects`，畫面需提示重新啟動服務與載入規格。

Commit 審查明確呈現「選擇 Commit → 檢視異動檔案 → 確認後推送」。人工確認與實際推送仍為兩個步驟。

## 無障礙與響應式

- 提供跳至主要內容連結、語意化導覽、可見焦點與通知 live region。
- Modal 提供 dialog 語意、初始焦點、Tab 焦點限制、Escape 關閉與焦點還原。
- 表單保留可見標籤，控制項具有 name，Repo 與路徑欄位停用拼字檢查。
- 狀態同時使用文字與顏色，不只依賴顏色。
- 小螢幕導覽改為可捲動橫列；專案規則改為帶欄位標籤的卡片，讓同步按鈕直接可見。其他大型比較表格在自己的容器內水平捲動，頁面本身不溢出。
- 行動版表單字級至少 16px，主要按鈕至少 44px 高。
- `prefers-reduced-motion` 停用動畫及過場。
- 時間保留 `YYYY-MM-DD HH:mm:ss` 格式，透過 `Intl.DateTimeFormat` 取得各時間欄位。

## 靜態檔案

Java 靜態 handler 以位元組回傳檔案，TTF 使用 `font/ttf`；HTML、JavaScript 與 CSS 設定正確 MIME。`Cache-Control: no-cache` 允許瀏覽器重新驗證，避免更新後持續使用舊介面。

## 驗證

`tests/ui-smoke.cjs` 是開發階段的 Playwright 回歸測試；部署執行不依賴它。請對隔離的 Java 服務執行測試，測試會攔截業務 API、提供模擬專案資料，不執行真實 Git 操作。

驗證範圍包含導覽、篩選空狀態還原、彈窗焦點、批次勾選與輪詢、原批次重跑、Commit 檔案清單、375/768/1024/1440px 排版、reduced motion 與本地字型回傳。

```bash
UI_TEST_URL=http://127.0.0.1:18081 node tests/ui-smoke.cjs
```

開發機需提供 Playwright 與其瀏覽器；可透過 `UI_TEST_BROWSER` 指定既有 Chrome 執行檔，`UI_ARTIFACTS_DIR` 指定截圖輸出位置。
