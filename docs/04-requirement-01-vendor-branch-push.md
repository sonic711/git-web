# 需求 01：廠商分支推送到指定目標 Remote

## 需求描述

系統必須支援將廠商 repo 的指定 branch，推送到使用者所設定的目標 remote / branch。

範例：

- 廠商 `SIT` 分支 -> 目標 remote A 的 `SIT`
- 廠商 `SIT2` 分支 -> 目標 remote B 的 `SIT2`
- 廠商 `UAT` 分支 -> 目標 remote C 的 `UAT`

## 目的

讓使用者不需手動輸入 Git 指令，只需在 Web UI 中選擇規則並執行同步。

## 已確認規格

- 廠商來源使用 `HTTPS` repo URL。
- 目標 remote 使用 `SSH` URL。
- 若本機指定目錄沒有 repo，系統需先 clone。
- 若本機指定目錄已有 repo，系統需直接使用並更新。
- UI 用 `checkbox` 控制本次同步是否執行 `git push -f`。
- 每次只允許執行一筆 rule。
- 本機已安裝 `git`，由 `Java 17` 呼叫執行。
- 來源與目標分支通常同名，系統應支援同名分支為預設模式。
- 某些 rule 可設定為僅允許手動同步。
- 某些 rule 可設定為同步前必須先檢視差異並人工確認。
- 使用者可在 UI 上修改 remote、branch、排程與同步規則，且修改結果必須寫回主設定檔。

## 功能拆解

此需求至少包含以下能力：

1. 設定廠商 repo URL。
2. 設定本機 repo 目錄。
3. 設定來源 branch。
4. 設定目標 remote tab。
5. 設定目標專案名稱 `.git`。
6. 設定目標 branch。
7. 設定來源與目標分支是否預設同名。
8. 設定此規則是否允許 force push。
9. 設定此規則是否僅允許手動同步。
10. 設定此規則是否需要 review gate。
11. 執行單筆同步並回報結果。

## 資料結構

## Remote 設定

```json
{
  "id": "targetA",
  "name": "SIT",
  "baseUrl": "git@target.example.com:team/"
}
```

## Mapping 設定

```json
{
  "id": "map-sit",
  "name": "Vendor SIT to Target A SIT",
  "vendorRepoUrl": "https://vendor.example.com/project.git",
  "localWorkspaceRoot": "D:/git-workspace",
  "localProjectName": "vendor-project",
  "sourceBranch": "SIT",
  "targetRemoteId": "targetA",
  "targetRepoName": "project-a.git",
  "targetBranch": "SIT",
  "sameBranchNameExpected": true,
  "enabled": true,
  "allowForcePush": true,
  "manualOnly": false,
  "reviewRequired": false
}
```

## UI 需求

## 設定頁

使用者必須可設定：

- 規則名稱
- 廠商 repo URL
- 本機目錄
- 來源 branch
- 目標 remote tab
- 目標 repo 名稱 `.git`
- 目標 branch
- 是否預設同名分支
- 規則啟用狀態
- 是否允許 force push
- 是否僅允許手動同步
- 是否需要 review gate

## 列表頁

使用者必須可查看：

- 所有 project / rule 設定
- 廠商 repo URL
- 本機目錄
- 來源 branch
- 目標 remote
- 目標 branch
- 最後執行時間
- 最後執行狀態
- `Manual Only` 標記
- `Review Required` 標記
- `Force Push` checkbox
- 驗證按鈕
- 查看差異按鈕
- 同步按鈕

規則行為：

- 若 `allowForcePush=false`，UI 不得允許勾選 force push。
- 若 `allowForcePush=true`，使用者可在執行前決定本次是否使用 `-f`。
- 若 `manualOnly=true`，UI 不得顯示或啟用排程開關。
- 若 `reviewRequired=true`，UI 必須先完成 commit 檢視、挑選要同步的 commit 與人工確認，才可執行同步。
- 使用者在 UI 修改規則後，系統必須立即寫回 `config/settings.json`。

## 同步流程

當使用者點擊「同步」時，系統應依序執行：

1. 讀取 project 與 rule 設定。
2. 讀取對應 remote 設定。
3. 驗證 project 與 rule 為啟用狀態。
4. 檢查實際 repo 路徑是否存在。
5. 若不存在，從 `vendorRepoUrl` clone。
6. 若存在，驗證該目錄是有效 Git repo。
7. 驗證該 repo 的來源與 `vendorRepoUrl` 相符。
8. 執行 `git fetch origin --prune`。
9. 驗證 `origin/<sourceBranch>` 存在。
10. 將本地 `sourceBranch` 強制對齊 `origin/<sourceBranch>`，避免廠商 force push 後本地歷程偏移。
11. 執行 `git pull --ff-only origin <sourceBranch>`。
12. 以 `remote.baseUrl + rule.targetRepoName` 組出完整目標 remote URL。
13. 建立或更新目標 remote。
14. 若 rule 設定為 `reviewRequired=true`，先產出 ahead commit 清單。
15. 允許使用者挑選本次要同步的 commit。
16. 依 checkbox 狀態決定本次使用一般 push 或 `git push -f`。
17. 記錄結果並回傳 UI。

## Review Gate 流程

對於例如 `UAT -> UAT` 這類需要人工檢視的規則，系統應支援：

1. rule 設定 `manualOnly=true`
2. rule 設定 `reviewRequired=true`
3. 使用者先點擊「查看差異」
4. UI 顯示 ahead commit 清單
5. 使用者點選單一 commit 時，可看到該 commit 的異動檔案清單
6. 使用者勾選要同步的 commit，並完成人工確認後才可執行同步

commit-based push 限制：

- 後端會以目標 branch 為基準建立暫時分支，並以 `git cherry-pick` 套用選取的 commit。
- 若選取 commit 依賴未選取的前置 commit，可能發生 cherry-pick conflict。
- 若目標 branch 已修改同一段內容，也可能發生 cherry-pick conflict。
- 發生 conflict 時，本次同步應失敗並中止，不進行 push。
- 目前不提供 UI 解衝突，也不會自動改成整支 branch push。

## Git 指令邏輯

以下為建議流程：

```bash
git clone <vendorRepoUrl> <localRepoPath>
git -C <localRepoPath> fetch origin --prune
git -C <localRepoPath> rev-parse --verify refs/remotes/origin/<sourceBranch>
git -C <localRepoPath> checkout -B <sourceBranch> origin/<sourceBranch>
git -C <localRepoPath> reset --hard refs/remotes/origin/<sourceBranch>
git -C <localRepoPath> pull --ff-only origin <sourceBranch>
git -C <localRepoPath> remote add <generatedTargetRemoteName> <targetUrl>
git -C <localRepoPath> remote set-url <generatedTargetRemoteName> <targetUrl>
git -C <localRepoPath> push <generatedTargetRemoteName> <sourceBranch>:refs/heads/<targetBranch>
git -C <localRepoPath> push -f <generatedTargetRemoteName> <sourceBranch>:refs/heads/<targetBranch>
```

實際執行時，不需每次都執行 `remote add` 與 `remote set-url`；應由系統判斷 remote 是否已存在後再處理。

## 驗證規則

同步前至少驗證以下條件：

1. `vendorRepoUrl` 不可為空。
2. `localWorkspaceRoot` 不可為空。
3. `localProjectName` 不可為空。
4. `sourceBranch` 不可為空。
5. `targetRemoteId` 必須存在。
6. `targetRepoName` 不可為空，且第一版要求以 `.git` 結尾。
7. `targetBranch` 不可為空。
8. rule 必須為啟用狀態。
9. 若本機目錄已存在，必須是有效 Git repo。
10. 若本機目錄已存在，repo 來源必須與 `vendorRepoUrl` 相符。
11. 若 `forcePush=true`，rule 必須允許 force push。
12. 若 `manualOnly=true`，不得由排程觸發。
13. 若 `reviewRequired=true`，同步請求必須附帶人工確認狀態。

## 錯誤情境

系統至少要能明確處理以下錯誤：

- clone 失敗
- 本機目錄存在但不是 Git repo
- 本機 repo 與設定的廠商 URL 不一致
- 來源 branch 不存在
- remote URL 無效
- SSH 權限不足
- HTTPS 認證失敗
- push 被拒絕
- selected commit 無法 cherry-pick 到目標 branch
- force push 被使用者規則禁止
- manual-only 規則被排程觸發
- review-required 規則未先確認就嘗試同步

## API 契約

## 驗證 API

`POST /api/rules/{ruleId}/validate`

回應範例：

```json
{
  "projectId": "fsap-adm",
  "ruleId": "rule-sit",
  "ok": true,
  "checks": [
    { "key": "mapping_enabled", "ok": true },
    { "key": "repo_ready", "ok": true },
    { "key": "branch_exists", "ok": true },
    { "key": "target_remote_template_exists", "ok": true },
    { "key": "target_repo_name_valid", "ok": true }
  ]
}
```

## 同步 API

`POST /api/rules/{ruleId}/sync`

請求範例：

```json
{
  "forcePush": true,
  "reviewConfirmed": true,
  "selectedCommitIds": ["abc1234", "def5678"]
}
```

回應範例：

```json
{
  "runId": "20260420T184500-map-sit",
  "projectId": "fsap-adm",
  "ruleId": "rule-sit",
  "status": "success",
  "sourceBranch": "SIT",
  "targetRemoteId": "targetA",
  "targetBranch": "SIT",
  "forcePush": true,
  "reviewConfirmed": true,
  "summary": "Push completed",
  "logPath": "2026-04-20.log"
}
```

## 差異檢視 API

`POST /api/rules/{ruleId}/diff`

回應範例：

```json
{
  "projectId": "fsap-adm",
  "ruleId": "rule-uat",
  "sourceBranch": "UAT",
  "targetBranch": "UAT",
  "summary": {
    "aheadCommits": 2,
    "changedFiles": 6
  },
  "commits": [
    {
      "id": "abc1234",
      "title": "Fix payment validation",
      "author": "SeanLiu",
      "committedAt": "2026-04-23T10:00:00+08:00",
      "selectable": true
    }
  ]
}
```

`GET /api/rules/{ruleId}/diff/commits/{commitId}/files`

回應範例：

```json
{
  "commitId": "abc1234",
  "title": "Fix payment validation",
  "files": [
    {
      "status": "M",
      "path": "src/payment/Validator.java"
    },
    {
      "status": "A",
      "path": "src/payment/ValidatorTest.java"
    }
  ]
}
```

## 驗收標準

此需求完成時，至少應滿足：

1. 可建立兩筆以上 rule。
2. 可執行 `SIT -> targetA/SIT`。
3. 可執行 `SIT2 -> targetB/SIT2`。
4. 若本機目錄不存在，系統會先 clone。
5. 若本機目錄已存在，系統會直接 fetch 後同步。
6. UI 可透過 checkbox 控制本次是否使用 `-f`。
7. `UAT` 之類的規則可設定為 manual-only 且 review-required。
8. 對 review-required 規則，UI 必須先提供 ahead commit 清單與單一 commit 的檔案清單檢視。
9. 實際 patch 內容不在本階段提供，列為後續功能。
10. UI 修改 remote、branch、排程與規則設定後，必須寫回主設定檔。
11. UI 能顯示成功或失敗結果。
12. 每次執行都會留下 log。
13. 不依賴資料庫。
