# Phase 2：來源與目標版本一致性比對

## 目的

使用者需經常確認來源 branch 與目標 branch 是否為相同程式版本。系統不得只比較 commit hash，因為 commit-based sync 使用 `cherry-pick` 後，即使程式內容相同，commit hash 也會不同。

本功能只適用於 `mode=sync` 的規則。`mode=download-only` 沒有目標 remote，不提供此比對。

## 比對基準

系統需同時取得來源與目標的：

- 最新 commit hash
- 最新 tree hash
- 雙方各自獨有的 commit 數量

建議 Git 指令：

```bash
git fetch origin +refs/heads/<sourceBranch>:refs/remotes/origin/<sourceBranch>
git fetch <targetRemote> +refs/heads/<targetBranch>:refs/remotes/<targetRemote>/<targetBranch>
git rev-parse origin/<sourceBranch>
git rev-parse <targetRemote>/<targetBranch>
git rev-parse origin/<sourceBranch>^{tree}
git rev-parse <targetRemote>/<targetBranch>^{tree}
git rev-list --left-right --count <targetRemote>/<targetBranch>...origin/<sourceBranch>
```

`tree hash` 代表該 commit 下的檔案路徑、檔案內容與 Git 檔案模式。它不包含 commit message、作者、時間或父 commit。

fetch 使用前置 `+` 的 refspec，確保來源或目標曾 force push 時，本地 remote-tracking ref 仍會更新到遠端最新 HEAD。

## 狀態判定

### `IDENTICAL`

- source commit hash 等於 target commit hash
- source tree hash 等於 target tree hash

結論：HEAD 與程式內容完全一致。

### `CONTENT_IDENTICAL`

- source commit hash 不等於 target commit hash
- source tree hash 等於 target tree hash

結論：程式內容一致，但歷程不同。常見原因是 cherry-pick、rebase 或重新建立 commit。

### `DIFFERENT`

- source tree hash 不等於 target tree hash

結論：程式內容或檔案模式不同。

### `TARGET_MISSING`

- 目標 branch 不存在

結論：無法進行版本一致性比對。

### `CHECK_FAILED`

- fetch、revision resolve 或其他 Git 指令失敗

結論：比對失敗，需顯示簡短錯誤並保留完整 log。

## Ahead / Behind 定義

指令：

```bash
git rev-list --left-right --count <targetRef>...<sourceRef>
```

回傳兩個數字：

- 第一個數字：只存在目標 branch 的 commit 數量，API 命名為 `targetOnlyCommits`
- 第二個數字：只存在來源 branch 的 commit 數量，API 命名為 `sourceOnlyCommits`

這兩個數字只描述 commit graph，不代表程式內容一定不同。最終內容一致性仍以 tree hash 為準。

## 操作流程

1. 使用者在 sync rule 點擊 `版本比對`。
2. 後端確認 project、rule 與 target remote 設定有效。
3. 後端確保本地 repo 已存在，必要時 clone。
4. 後端 fetch 最新來源 branch 與目標 branch。
5. 後端取得雙方 commit hash 與 tree hash。
6. 後端計算 `targetOnlyCommits` 與 `sourceOnlyCommits`。
7. 後端依狀態判定規則回傳結果。
8. UI 顯示摘要、完整 hash 與檢查時間。

## 後續：同步完成後自動檢查

手動版本比對完成後，可再擴充為：`mode=sync` 的 branch push 或 commit-based push 成功後，後端自動執行一次版本比對。

- `IDENTICAL` 或 `CONTENT_IDENTICAL`：同步 job 可維持成功。
- `DIFFERENT`：同步 job 應標示為警告或驗證失敗，不得只回報 push 成功。
- `TARGET_MISSING` 或 `CHECK_FAILED`：同步結果需清楚標示後置驗證未完成，完整錯誤寫入 log。

本次規格的第一個實作範圍是手動 `版本比對`。同步後自動檢查是後續項目，不應阻擋第一版交付。

## UI 顯示

列表操作新增 `版本比對` 按鈕。結果至少顯示：

- 比對狀態
- 比對時間
- source branch / target branch
- source commit hash / target commit hash
- source tree hash / target tree hash
- `sourceOnlyCommits`
- `targetOnlyCommits`

建議狀態文案：

- `IDENTICAL`：完全一致
- `CONTENT_IDENTICAL`：內容一致，歷程不同
- `DIFFERENT`：版本不同
- `TARGET_MISSING`：目標分支不存在
- `CHECK_FAILED`：版本比對失敗

hash 預設可顯示短值，滑鼠移入或展開後顯示完整值。

## API

新增：

```text
POST /api/rules/{ruleId}/version-compare
```

成功回應範例：

```json
{
  "projectId": "fsap-adm",
  "ruleId": "rule-sit",
  "status": "CONTENT_IDENTICAL",
  "checkedAt": "2026-06-11T14:30:00+08:00",
  "sourceBranch": "sit",
  "targetBranch": "sit",
  "sourceCommit": "1111111111111111111111111111111111111111",
  "targetCommit": "2222222222222222222222222222222222222222",
  "sourceTree": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "targetTree": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "sourceOnlyCommits": 2,
  "targetOnlyCommits": 0,
  "commitIdentical": false,
  "contentIdentical": true,
  "message": "Content is identical, but commit history differs"
}
```

## 非目標

- 不以 tag 判斷 branch 程式內容是否一致
- 不因 tree hash 不同而自動 force push
- 不自動合併來源與目標差異
- 不以檔案時間或工作目錄修改時間作為版本依據
