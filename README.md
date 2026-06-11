# Git Web Offline Spec

這個目錄存放正式需求規格，目標是在離線環境、無資料庫、僅有 `Java 17` 的條件下，提供可透過網頁介面操作 Git 的工具。

目前已確認的核心能力：

1. 使用者先建立一個專案，再在專案底下建立多條同步規則。
2. 系統保存一份全局本地主目錄，專案只保存廠商 repo 的 URL 與本地專案資料夾名稱；廠商 URL 可使用 `http`、`https` 或 `ssh`。
3. 規則保存來源 branch、目標 remote、目標 branch、排程與 force push 選項。
4. 同一個專案可同時有 `sit -> sit`、`sit2 -> sit2`、`uat -> uat` 等多條規則。
5. 自動同步規則仍可手動同步，不需為了 `-f` 額外建立新規則。
6. 系統會先檢查實際 repo 路徑是否已有 repo；若沒有則先 `clone`。
7. 推送前會先將本地來源 branch 對齊廠商 `origin/<sourceBranch>`。
8. 系統設定以可攜式設定檔保存，方便直接複製給其他人使用。
9. UI 可匯出 / 匯入主設定檔；匯出內容不包含全局本地主目錄，避免他人匯入後直接在原機器路徑 clone。
10. sync 規則可比較來源與目標的 commit hash、tree hash 與雙方獨有 commit 數量，確認歷程及程式內容是否一致。
11. Projects 列表可依關鍵字、Remote Tab、規則模式、執行方式與最後狀態篩選，避免規則數量增加後主畫面過長。

## 文件清單

- [memory.md](/Users/sonic711/Desktop/development/git-web/memory.md)
- [docs/01-product-overview.md](/Users/sonic711/Desktop/development/git-web/docs/01-product-overview.md)
- [docs/02-use-cases-and-flow.md](/Users/sonic711/Desktop/development/git-web/docs/02-use-cases-and-flow.md)
- [docs/03-technical-architecture.md](/Users/sonic711/Desktop/development/git-web/docs/03-technical-architecture.md)
- [docs/04-requirement-01-vendor-branch-push.md](/Users/sonic711/Desktop/development/git-web/docs/04-requirement-01-vendor-branch-push.md)
- [docs/05-open-questions.md](/Users/sonic711/Desktop/development/git-web/docs/05-open-questions.md)
- [docs/06-requirement-02-scheduled-sync.md](/Users/sonic711/Desktop/development/git-web/docs/06-requirement-02-scheduled-sync.md)
- [docs/07-config-schema.md](/Users/sonic711/Desktop/development/git-web/docs/07-config-schema.md)
- [docs/08-api-contract.md](/Users/sonic711/Desktop/development/git-web/docs/08-api-contract.md)
- [docs/09-java-module-design.md](/Users/sonic711/Desktop/development/git-web/docs/09-java-module-design.md)
- [docs/10-ui-spec.md](/Users/sonic711/Desktop/development/git-web/docs/10-ui-spec.md)
- [docs/11-phase-2-diff-cache.md](/Users/sonic711/Desktop/development/git-web/docs/11-phase-2-diff-cache.md)
- [docs/12-version-comparison.md](/Users/sonic711/Desktop/development/git-web/docs/12-version-comparison.md)
- [docs/13-rule-filtering.md](/Users/sonic711/Desktop/development/git-web/docs/13-rule-filtering.md)

## 已確認架構

- 前端：`HTML + JavaScript`
- 本機後端：`Java 17`
- Git 執行方式：Java 透過 `ProcessBuilder` 呼叫系統 `git`
- 設定儲存：本機可攜式 `JSON` 設定檔
- 執行狀態：本機 `state` 檔
- 紀錄儲存：本機 `log` 檔
- 差異快取：本機 `cache` 檔
- 資料庫：不使用

## 設計原則

- 網頁只負責操作與呈現，不直接執行 Git。
- 本機 Java 服務是唯一可執行 Git 指令的元件。
- 同步流程以單筆規則為最小執行單位。
- 第一版以單人使用為主，但保留未來部署到共用機器的擴充空間。
- 可複製的業務設定與本機執行狀態分離保存。
- `memory.md` 用於保留開發進度、已確認決策與最近 session 歷程，方便後續 agent 接手。
- 主設定檔是可匯入、可匯出的單一來源，UI 修改後必須立即回寫。
- git 只追蹤 [config/settings.example.json](/Users/sonic711/Desktop/development/git-web/config/settings.example.json)，不追蹤本機實際使用的 `config/settings.json`。

## 啟動方式

macOS / Linux:

```bash
./run.sh
```

若要調整 port，可直接修改 [run.sh](/Users/sonic711/Desktop/development/git-web/run.sh) 內的 `PORT`，或暫時覆蓋：

```bash
PORT=9090 ./run.sh
```

Windows Command Prompt:

```bat
run.bat
```

若要調整 port，可直接修改 [run.bat](/Users/sonic711/Desktop/development/git-web/run.bat) 內的 `set "PORT=8080"`。

Windows PowerShell:

```powershell
.\run.ps1
```

若要調整 port，可直接修改 [run.ps1](/Users/sonic711/Desktop/development/git-web/run.ps1) 內的 `$port = 8080`。

前提：

- 需安裝 `JDK 17`
- 需安裝 `git`
- 第一次可參考 `config/settings.example.json` 建立本機的 `config/settings.json`
- 本地主目錄改為全局設定，所有專案共用同一個 workspace root
