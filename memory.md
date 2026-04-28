# Project Memory

這份檔案用來保留跨 session 的開發記憶，避免後續 agent 或開發者重新理解需求時遺漏既有決策。

注意：

- `memory.md` 是開發記憶，不是正式需求的唯一依據。
- 正式規格以 `README.md` 與 `docs/` 內文件為準。
- 每次有重大需求變更、架構調整、實作完成或卡點時，都應更新此檔。

## 目前狀態

- 已開始實作 MVP。
- 已完成正式規格文件整理。
- 已確認需求 01：手動同步單筆 rule。
- 已確認需求 02：多專案定時推送。

## 已確認決策

- 執行環境只有 `Java 17`，且本機有安裝 `git`。
- 不使用 `Node.js`。
- 前端使用 `HTML + JavaScript`。
- 本機後端使用 `Java 17` localhost HTTP 服務。
- Git 執行方式為 Java 透過 `ProcessBuilder` 呼叫系統 `git`。
- 不使用資料庫。
- 廠商 repo 可使用 `HTTP`、`HTTPS` 或 `SSH`。
- 目標 remote 使用 `SSH`。
- 若本機目錄沒有 repo，需先 `clone`。
- 若本機目錄已有 repo，需直接 `fetch` 後同步。
- UI 使用 `checkbox` 控制本次是否 `git push -f`。
- 手動同步一次只執行一筆 rule。
- 設定需以可攜式設定檔保存，方便複製給其他人使用。
- 主設定檔與本機執行狀態檔分離保存。
- 系統需支援多專案定時推送。
- 來源與目標分支通常同名，系統需支援同名分支為預設模式。
- 某些 rule 需標記為 `manualOnly=true`，不得自動排程。
- 某些 rule 需標記為 `reviewRequired=true`，同步前需先看 ahead commit 並人工確認。
- UI 必須可隨時修改 remote、branch、是否自動同步等設定，並寫回 `config/settings.json`。
- 本機目錄應可由 UI 直接選資料夾，不以手打路徑為主要方式。
- Mappings 列表需提供行內 `Force Push` 與 `自動同步` 勾選。
- Remotes 需支援自訂 Tab / 群組，例如 `SIT`、`UAT`。
- Remote 已重構為「頁籤模板」模型，每個頁籤保存 `baseUrl`，Mapping 只填最後的 `project.git`。
- 新增與編輯 Mapping / Remote 必須使用 popup modal，不佔主畫面中央區域。
- 需支援 Windows 啟動腳本，至少提供 `run.bat` 與 `run.ps1`。
- 本地工作目錄已改成全局 `localWorkspaceRoot` + 各專案 `localProjectName`，不再由專案各自保存主目錄。
- 同一本地專案可對應多筆 rule，且每筆 rule 使用獨立內部 remote 名稱。
- 同步前需先將本地 `sourceBranch` 強制對齊廠商 `origin/<sourceBranch>`，再執行 `git pull --ff-only`。
- 所有會呼叫後端 API 的按鈕操作都需顯示全畫面 loading overlay。
- 設定模型已從扁平 `mappings` 重構成 `projects[*].rules[*]`。
- `config/settings.json` 不應再被 git 追蹤，repo 只保留 `config/settings.example.json`。
- 若某條 rule 從未手動同步過，首次啟用自動同步後也必須立即進入排程，不可等到第一次人工執行後才開始。
- 專案列表需支援將同一專案底下的多條規則摺疊 / 展開，避免主畫面過長。
- 自動同步需允許不同本地 repo 的專案並行執行；只有限制同一本地 repo 的規則需排隊。
- UI 需提供可修改的全局本地主目錄設定，並寫回 `config/settings.json`。
- UI 的時間顯示格式統一為 `YYYY-MM-DD HH:mm:ss`，最後結果需顯示最後執行時間。
- log 改為每日單檔持續追加，檔名格式為 `YYYY-MM-DD.log`，且只保留當日一份。
- `查看差異` 需改為 commit-based review：先顯示 ahead commit 清單，再點選單一 commit 顯示異動檔案清單。
- review 畫面需支援勾選一個或多個 commit，並以 `selectedCommitIds` 執行同步。
- commit-based sync 目前透過 `git cherry-pick` 套用選取 commit，若 commit 有相依或目標 branch 已修改同一段內容，可能衝突並導致同步失敗。
- 實際 patch 內容不在本階段提供，列為後續功能。

## 檔案結構決策

- 正式規格入口：[README.md](/Users/sonic711/Desktop/development/git-web/README.md)
- 產品總覽：[docs/01-product-overview.md](/Users/sonic711/Desktop/development/git-web/docs/01-product-overview.md)
- 使用流程：[docs/02-use-cases-and-flow.md](/Users/sonic711/Desktop/development/git-web/docs/02-use-cases-and-flow.md)
- 技術架構：[docs/03-technical-architecture.md](/Users/sonic711/Desktop/development/git-web/docs/03-technical-architecture.md)
- 需求 01：[docs/04-requirement-01-vendor-branch-push.md](/Users/sonic711/Desktop/development/git-web/docs/04-requirement-01-vendor-branch-push.md)
- 待確認事項：[docs/05-open-questions.md](/Users/sonic711/Desktop/development/git-web/docs/05-open-questions.md)
- 需求 02：[docs/06-requirement-02-scheduled-sync.md](/Users/sonic711/Desktop/development/git-web/docs/06-requirement-02-scheduled-sync.md)
- 設定檔 Schema：[docs/07-config-schema.md](/Users/sonic711/Desktop/development/git-web/docs/07-config-schema.md)
- API 契約：[docs/08-api-contract.md](/Users/sonic711/Desktop/development/git-web/docs/08-api-contract.md)
- Java 模組設計：[docs/09-java-module-design.md](/Users/sonic711/Desktop/development/git-web/docs/09-java-module-design.md)
- UI 規格：[docs/10-ui-spec.md](/Users/sonic711/Desktop/development/git-web/docs/10-ui-spec.md)
- Phase 2 差異快取：[docs/11-phase-2-diff-cache.md](/Users/sonic711/Desktop/development/git-web/docs/11-phase-2-diff-cache.md)

## 目前設定檔策略

- 主設定檔：`config/settings.json`
- 本機執行狀態檔：`state/runtime-state.json`
- 執行紀錄：`logs/YYYY-MM-DD.log`
- 差異快取：`cache/diff/*`

主設定檔保存：

- remotes
- projects
- rules
- schedule
- manualOnly
- reviewRequired
- sameBranchNameExpected
- remote baseUrl
- rule targetRepoName

本機執行狀態檔保存：

- lastRunAt
- lastStatus
- lastRunSource
- nextRunAt
- running
- lastMessage

## 尚未實作

- API 錯誤碼細緻化
- UI 細節優化
- 真機 HTTP 啟動驗證

## 主要待確認事項

- repo URL 比對規則要不要正規化
- log 保留政策
- 排程格式是否只做固定間隔
- 共用機器時的併發保護
- `git push -f` 未來是否改成 `--force-with-lease`
- commit-based push 是否限制為連續 commit

## 建議下一步

1. 依 `docs/07-config-schema.md` 建立設定模型與驗證。
2. 依 `docs/09-java-module-design.md` 建立 Java 專案骨架。
3. 依 `docs/08-api-contract.md` 實作 API。
4. 依 `docs/10-ui-spec.md` 建立第一版 UI。

## Session Log

### 2026-04-20

- 建立專案規格文件。
- 將原本泛用方案收斂為 `Java 17 + git CLI + HTML/JS`。
- 確認設定需為可攜式設定檔。
- 新增多專案定時推送需求。
- 建立本 `memory.md` 作為跨 session 記憶檔。
- 補充 manual-only、review-required 與 UI 回寫設定檔需求。
- 補齊設定檔 schema、API 契約、Java 模組設計與 UI 規格文件。
- 建立 `src/app` Java MVP 骨架。
- 建立 `static/` 單頁 UI。
- 建立 `run.sh` 啟動腳本。
- `javac` 編譯成功。
- sandbox 不允許綁定 localhost port，因此未能在此環境完成 HTTP 啟動驗證。
- 補上本機資料夾選擇 API、Mappings 行內 checkbox、Remotes 分組 Tab。
- 補上 Mapping / Remote 刪除功能。
- Diff 頁升級為 commit list + changed files list。
- 列表加入更清楚的最後狀態、觸發來源與錯誤訊息。
- 所有主要按鈕操作都會在畫面上顯示成功或失敗通知。
- 將 Remote 模型從完整 URL 重構為 `baseUrl` 頁籤模板。
- Mapping 改為保存 `targetRepoName`，由後端組合完整 target remote URL。
- 新增與編輯 Mapping / Remote 改為 popup modal，主畫面聚焦在推送列表。
- 補上 Windows 啟動腳本 `run.bat` 與 `run.ps1`。
- 將本地 repo 設定改為「主目錄 + 專案資料夾名稱」模式，並保留舊 `localRepoPath` 相容轉換。
- 同一本地 repo 新增執行鎖，避免多筆 rule 同時操作互相干擾。
- 同步流程改為先對齊 vendor branch，再 pull，再 push，避免廠商 force push 造成歷程偏移。
- 前端新增全畫面 loading overlay，覆蓋所有主要按鈕操作。
- UI 改為專案視角：先建立專案，再在專案底下建立多條同步規則。
- repo 改為只追蹤 `config/settings.example.json`，本機 `config/settings.json` 已從 git index 移除。
- 修正首次啟用自動同步但尚未手動同步過的 rule 不會執行的問題；scheduler 現在會將第一次 `nextRunAt` 設為當下時間。
- 修正自動同步在不同專案間仍被串行化的問題；排程器現在只負責觸發，實際同步交由背景 worker 執行，不同 repo 可並行。
- 將本地主目錄從專案層級改成全局設定，專案只保留 `localProjectName`，並保留舊設定檔的相容轉換。
- 前端時間顯示改為 `YYYY-MM-DD HH:mm:ss`，最後結果欄位已補上最後執行時間。
- `LogService` 已改成只保留一天內的 log，啟動與寫入新 log 時都會清理過期檔案。
- `查看差異` 已改成開啟獨立 review 視窗，主流程改為 commit-based review。
- review 視窗先讀 ahead commit 摘要快取，必要時可手動刷新最新差異。
- 點擊單一 commit 時，後端回傳該 commit 的異動檔案清單，不再以舊 patch viewer 作為主流程。
- 使用者可勾選一個或多個 commit，並以 `selectedCommitIds` 直接執行同步。
- selected commit 會由後端依來源歷史順序 cherry-pick；若發生 conflict，後端會中止同步，不會 push。
- 實際 patch 內容已從本階段正式規格移除，列為後續功能。
