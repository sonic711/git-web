# Git Web Offline Spec

這個目錄存放正式需求規格，目標是在離線環境、無資料庫、僅有 `Java 17` 的條件下，提供可透過網頁介面操作 Git 的工具。

目前已確認的核心能力：

1. 使用者可設定廠商 repo 的 `HTTPS` URL、來源 branch、本機專案目錄。
2. 系統會先檢查指定目錄是否已有 repo；若沒有則先 `clone`。
3. 使用者可設定目標 remote 的 `SSH` URL 與目標 branch。
4. 系統可將廠商來源 branch 推送到指定目標 remote / branch。
5. UI 以 `checkbox` 控制本次同步是否使用 `git push -f`。
6. 一次只執行一筆 mapping 規則。
7. 系統設定以可攜式設定檔保存，方便直接複製給其他人使用。
8. 系統需支援多專案定時推送到指定 remote。
9. 某些 mapping 可設定為 `manualOnly`，不得排程。
10. 某些 mapping 可設定為 `reviewRequired`，需先看差異並人工確認。
11. 使用者可在 UI 隨時修改 remote、branch、排程與規則，且修改結果必須寫回 `config/settings.json`。

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

## 已確認架構

- 前端：`HTML + JavaScript`
- 本機後端：`Java 17`
- Git 執行方式：Java 透過 `ProcessBuilder` 呼叫系統 `git`
- 設定儲存：本機可攜式 `JSON` 設定檔
- 執行狀態：本機 `state` 檔
- 紀錄儲存：本機 `log` 檔
- 資料庫：不使用

## 設計原則

- 網頁只負責操作與呈現，不直接執行 Git。
- 本機 Java 服務是唯一可執行 Git 指令的元件。
- 同步流程以單筆規則為最小執行單位。
- 第一版以單人使用為主，但保留未來部署到共用機器的擴充空間。
- 可複製的業務設定與本機執行狀態分離保存。
- `memory.md` 用於保留開發進度、已確認決策與最近 session 歷程，方便後續 agent 接手。
- 主設定檔是可匯入、可匯出的單一來源，UI 修改後必須立即回寫。
