# 使用情境與流程

## 角色

- 使用者：設定 repo、branch、remote、project 與 rule，並手動執行同步。
- 系統：檢查 repo 狀態、執行 Git、回傳結果、寫入 log。

## 主要使用情境

## 情境 1：目錄不存在，先 clone 再同步

使用者建立一筆規則：

- 廠商 repo：`https://vendor.example.com/project.git`
- 本地主目錄：`D:/git-workspace`
- 本地專案資料夾：`vendor-project`
- 來源 branch：`SIT`
- 目標 remote：`git@target.example.com:team/project.git`
- 目標 branch：`SIT`

執行同步時：

1. 系統檢查實際 repo 路徑是否存在有效 Git repo。
2. 若不存在，先從廠商 repo clone 到指定目錄。
3. clone 完成後先對齊廠商來源分支，再 pull 最新狀態。
4. 將 `SIT` 推送到目標 remote 的 `SIT`。

## 情境 2：目錄已存在，直接 fetch 後同步

若實際 repo 路徑已是有效 Git repo，系統應：

1. 驗證該 repo 與設定相符。
2. 執行 `fetch origin --prune`。
3. 將本地來源分支強制對齊 `origin/<sourceBranch>`。
4. 執行 `git pull --ff-only origin <sourceBranch>`。
5. 推送到目標 remote / branch。

## 情境 3：使用 force push

使用者在 UI 勾選「Force Push」checkbox 後，同步流程改為：

1. 執行前仍做相同驗證。
2. push 指令附加 `-f`。
3. UI 必須清楚標示本次使用了強制推送。

## 情境 4：一次只執行一筆規則

第一版主畫面只允許使用者一次執行一筆 rule，避免：

- 多筆同步互相干擾
- 錯誤訊息難以對應
- log 難以追查

## 情境 5：複製設定檔到另一台機器

使用者希望把既有規則交給另一位同事使用時，系統應支援：

1. 以單一主設定檔保存全局主目錄、remotes、projects、rules 與排程設定。
2. 同事只需複製設定檔到指定目錄即可載入。
3. 本機執行狀態與 log 不應影響主設定檔的可攜性。

## 情境 6：排程自動同步多個專案

使用者設定多個專案與多筆 rule 排程後，系統應可：

1. 在背景定時檢查可執行的排程。
2. 逐筆觸發對應 rule 的同步。
3. 為每次排程執行留下 log 與結果。
4. 避免同一筆排程在尚未完成時重複啟動。

## 情境 7：禁止排程且需 review 後手動同步

某些分支例如 `UAT`，使用者希望：

1. 不可自動排程同步。
2. 只能手動同步。
3. 同步前先查看來源與目標的差異。
4. 人工確認後才可執行同步。

系統應支援將 rule 標記為：

- `manualOnly=true`
- `reviewRequired=true`

## 情境 8：在 UI 修改設定並寫回設定檔

使用者可在 UI 中直接修改：

- 是否啟用自動同步
- remote URL
- 來源 branch
- 目標 branch
- 是否允許 force push
- 是否需要 review gate

修改後系統應：

1. 驗證輸入格式。
2. 更新 `config/settings.json`。
3. 重新載入排程與規則。
4. 讓其他人可直接複製該設定檔匯入使用。

## 單筆同步標準流程

1. 使用者在列表頁選擇一筆 rule。
2. 使用者決定本次是否勾選 `Force Push`。
3. UI 呼叫本機 Java API 建立同步 job。
4. Java API 立即回傳 `jobId` 與 `queued` 狀態。
5. UI 透過列表或 job API 顯示 `queued / running / success / failed`。
6. 背景 worker 載入設定檔。
7. 系統檢查實際 repo 路徑是否已有 repo。
8. 若沒有 repo，執行 clone。
9. 若已有 repo，驗證該目錄為有效 Git repo。
10. 執行 `git fetch origin --prune`。
11. 將本地來源分支對齊 `origin/<sourceBranch>`，並執行 `git pull --ff-only origin <sourceBranch>`。
12. 建立或更新目標 remote。
13. 依 checkbox 狀態決定一般 push 或 `push -f`。
14. 若 rule 啟用 review gate，先檢查是否已完成人工確認。
15. 寫入 log 並更新 job / runtime state。

## Review Gate 流程

1. 使用者在列表頁選擇一筆 `reviewRequired=true` 的 rule。
2. UI 先呼叫 diff API 取得來源與目標差異。
3. UI 顯示目前 ahead commit 清單。
4. 使用者點擊單一 commit，可查看該 commit 的異動檔案清單。
5. 使用者勾選一個或多個 commit 作為本次要同步的內容。
6. 使用者人工確認後，才可點擊同步。
7. 後端驗證該次同步請求帶有人工確認標記與選取的 commit 清單後，建立背景同步 job。
8. 背景 worker 以目標 branch 為基準建立暫時分支，並依來源歷史順序 `cherry-pick` 選取的 commit。
9. 若 cherry-pick 發生衝突，本次同步 job 失敗，不會 push 到目標 remote。

## 失敗處理流程

若任一步驟失敗，系統應：

1. 立即停止後續流程。
2. 回傳明確錯誤碼。
3. 回傳可讀錯誤訊息。
4. 保留完整執行 log。

## UI 頁面建議

目前建議 4 個頁面：

1. Mapping 列表頁
2. Mapping 設定頁
3. 執行結果 / log 頁
4. 排程設定頁

## UI 顯示欄位

列表頁至少顯示：

- 規則名稱
- 廠商 repo URL
- 本機目錄
- 來源 branch
- 目標 remote
- 目標 branch
- 預設是否允許 force
- 是否僅允許手動同步
- 是否需要 review
- 最後執行時間
- 最後執行狀態
- 同步按鈕
- 是否啟用排程
- 下次排程時間
