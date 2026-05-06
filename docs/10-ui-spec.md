# UI 規格

## 目的

定義第一版 Web UI 的頁面、欄位與互動，讓前端與後端對齊。

## 頁面列表

1. Mapping 列表頁
2. Mapping Modal
3. Remote Tab Modal
4. 排程設定頁
5. Diff / Review 頁
6. 執行結果 / Log 頁

## 1. Mapping 列表頁

用途：

- 顯示所有規則
- 執行手動同步
- 快速看到排程與 review 狀態
- 作為主要操作頁面

欄位：

- 規則名稱
- 廠商 repo URL
- 本地主目錄
- 本地專案資料夾名稱
- 來源 branch
- 目標 remote tab
- 目標 repo 名稱
- 目標 branch
- `Manual Only`
- `Review Required`
- `Allow Force Push`
- 排程是否啟用
- 下次執行時間
- 最後執行狀態

操作：

- `編輯`
- `查看差異`
- `同步`
- `查看 Log`
- `刪除`
- 行內 `Force Push` checkbox
- 行內 `自動同步` checkbox

同步互動：

1. Mapping 建立完成後，應先顯示在列表中
2. 若 `allowForcePush=true`，列表行內顯示 `Force Push` checkbox
3. `自動同步` checkbox 直接對應 `schedule.enabled`
4. `manualOnly=true` 時，`自動同步` checkbox 必須 disabled
5. 排程執行後 UI 必須自動刷新最後結果與下次執行時間
6. 若 `reviewRequired=true`，`同步` 前需先點 `查看差異`
7. 若 `reviewRequired=true` 且未人工確認，`同步` 按鈕需 disabled
8. 若 review 畫面尚未選取 commit，不得允許 commit-based push
9. 列表需清楚顯示 `lastStatus`、`lastRunSource` 與失敗訊息
10. 任何會呼叫後端 API 的按鈕操作，都需顯示明確 loading overlay，直到作業完成或失敗

## Settings 區

用途：

- 修改全局本地主目錄
- 匯出可攜式設定檔
- 匯入可攜式設定檔

操作：

- `儲存全局設定`
- `匯出設定檔`
- `匯入設定檔`
- `選資料夾`

互動規則：

1. `匯出設定檔` 產生的 JSON 不得包含 `localWorkspaceRoot`
2. 使用者匯入設定檔後，若本機尚未設定 `localWorkspaceRoot`，UI 應提示先設定全局本地主目錄
3. 匯入成功後需重新載入 remotes、projects、rules 與 scheduler 狀態
4. `選資料夾` 在 macOS 應優先開啟原生目錄選擇視窗；在 Windows 與其他桌面環境，需使用帶 owner 視窗的選擇器，避免對話框跳到背景
5. 使用者尚未按 `儲存全局設定` 前，自動刷新不得覆寫欄位中的未儲存路徑

## 2. Mapping Modal

用途：

- 以 popup modal 建立或修改同步規則

欄位：

- 規則名稱
- 廠商 repo URL
- 本地主目錄
- `選資料夾` 按鈕
- 本地專案資料夾名稱
- 實際 repo 路徑預覽
- 來源 branch
- 目標 remote tab 下拉選單
- 目標 repo 名稱 `.git`
- 完整目標 URL 預覽
- 目標 branch
- `來源與目標分支同名` checkbox
- `啟用規則` checkbox
- `允許 Force Push` checkbox
- `僅允許手動同步` checkbox
- `同步前必須 Review` checkbox

互動規則：

1. 勾選 `來源與目標分支同名` 時，UI 自動把 `targetBranch` 帶成 `sourceBranch`
2. 勾選 `僅允許手動同步` 時，排程欄位需 disabled
3. 本地主目錄應優先使用 `選資料夾`，避免手動輸入
4. 若本地專案資料夾名稱為空，UI 可依 `vendorRepoUrl` 自動推導預設值
5. 選取 remote tab 或輸入 repo 名稱時，UI 應即時預覽完整 target URL
6. 儲存成功後必須寫回 `config/settings.json`

## 3. Remote Tab Modal

用途：

- 以 popup modal 建立或修改 remote tab 模板

欄位：

- Remote ID
- 頁籤名稱
- Base URL
- 啟用狀態

互動規則：

1. Remote ID 建立後不可輕易變更，避免 rule 關聯失效
2. 可由使用者自訂頁籤，例如 `SIT`、`UAT`
3. Base URL 只保存共用倉庫路徑，不含最後專案名稱
4. Rule 建立時只需補上最後的 `project.git`
5. 列表需支援刪除 remote
6. 儲存成功後必須寫回 `config/settings.json`

## 4. 排程設定頁

用途：

- 查看所有 rule 的排程資訊
- 快速調整是否自動同步

欄位：

- 規則名稱
- 是否自動同步
- 間隔分鐘數
- `Manual Only`
- 最後執行時間
- 下次執行時間

互動規則：

1. `manualOnly=true` 的規則不可啟用自動同步
2. 修改後必須立即寫回 `config/settings.json`
3. 若有修改排程開關或間隔，後端需先清除該 rule 舊的 `nextRunAt`
4. 儲存成功後後端需重新載入 scheduler

## 5. Diff / Review 頁

用途：

- 顯示 review-required rule 的 ahead commit 清單
- 顯示單一 commit 的異動檔案清單
- 讓使用者以挑選 commit 的方式執行同步

欄位：

- 規則名稱
- 來源 branch
- 目標 branch
- Ahead commits 數量
- Changed files 數量
- Commit list
- Selected commit count
- 單一 commit 的檔案清單

操作：

- `勾選 commit`
- `人工確認本次同步`
- `返回列表`
- `推送已選 commit`

互動規則：

1. 頁面開啟後，先顯示 ahead commit 清單
2. 點擊 commit 時，右側或下方顯示該 commit 的異動檔案清單
3. 使用者可勾選一個或多個 commit，作為本次要同步的內容
4. 只有在人工確認後才可按 `推送已選 commit`
5. 此確認僅對當次同步有效，不寫入主設定檔
6. 本階段不顯示實際 patch 內容，僅顯示檔案清單
7. UI 需提示使用者：只選部分 commit 時，若該 commit 依賴未選取的前置 commit，可能導致 cherry-pick 衝突
8. 若後端回傳 cherry-pick conflict，UI 應顯示「所選 commit 無法套用到目標 branch，請改選相關 commit 或改用整支同步」

## 6. 執行結果 / Log 頁

用途：

- 顯示最近一次執行結果
- 顯示 stdout / stderr / 狀態摘要
- 顯示操作成功或失敗

欄位：

- Run ID
- 規則名稱
- 觸發來源
- 是否 force push
- 是否 review confirmed
- 結果狀態
- stdout
- stderr

## UI 資料流

### 載入列表頁

1. 呼叫 `GET /api/system/config`
2. 呼叫 `GET /api/remotes`
3. 呼叫 `GET /api/projects`
4. 呼叫 `GET /api/schedules`
5. 合併畫面狀態

### 修改規則

1. 使用者在指定專案下編輯 rule 欄位
2. 送出 `PUT /api/projects/{projectId}/rules/{ruleId}`
3. 成功後刷新列表
4. UI 顯示成功或失敗訊息

### 修改 remote

1. 使用者編輯 remote
2. 送出 `PUT /api/remotes/{id}`
3. 成功後刷新 rule 編輯下拉選單與 remote tabs
4. UI 顯示成功或失敗訊息

### 選擇本機目錄

1. 使用者在 Mapping 編輯頁點擊 `選資料夾`
2. UI 呼叫 `POST /api/system/select-directory`
3. 後端開啟本機資料夾選擇器
4. 回填絕對路徑到 `localWorkspaceRoot`

### 查看差異並同步

1. 呼叫 `GET /api/rules/{ruleId}/diff-cache`
2. 若沒有快取或使用者要求刷新，呼叫 `POST /api/rules/{ruleId}/diff-cache/refresh`
3. 顯示 ahead commit 清單
4. 使用者點擊 commit，UI 呼叫 `GET /api/rules/{ruleId}/diff/commits/{commitId}/files`
5. 顯示該 commit 的異動檔案清單
6. 使用者勾選要同步的 commit
7. 使用者人工確認
8. 呼叫 `POST /api/rules/{ruleId}/sync`
9. request body 帶入 `selectedCommitIds`

### 刪除

1. 使用者點擊刪除按鈕
2. UI 顯示確認視窗
3. 送出 `DELETE /api/projects/{projectId}`、`DELETE /api/projects/{projectId}/rules/{ruleId}` 或 `DELETE /api/remotes/{id}`
4. 成功後刷新列表並顯示成功訊息

## 第一版 UI 優先順序

1. Mapping 列表頁
2. Mapping 編輯頁
3. Remote 編輯頁
4. 排程設定頁
5. Diff / Review 頁
6. Log 頁
