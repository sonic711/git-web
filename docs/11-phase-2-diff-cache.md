# Phase 2：Commit Review 快取機制

## 目的

解決 `查看差異` 在大型 repo、遠端較慢、ahead commit 較多時載入過慢或 timeout 的問題。

Phase 2 的核心方向不是顯示實際 patch，而是將 review 流程改成：

1. 可快速讀取的 ahead commit 摘要
2. 可按需讀取的單一 commit 檔案清單
3. 可從挑選 commit 直接執行同步

## 設計目標

- 開啟差異頁時優先顯示 ahead commit 清單
- 使用者可點選單一 commit 查看該 commit 的檔案清單
- 使用者可勾選一個或多個 commit 作為本次同步內容
- 不使用資料庫，所有快取以本機檔案實作
- 本階段不提供實際 patch 內容

## 範圍

### 本階段要做

- ahead commit 摘要快取
- 單一 commit 檔案清單快取
- 快取時間與狀態顯示
- 手動刷新最新差異
- 以 `selectedCommitIds` 執行同步

### 本階段不做

- 實際 patch 內容
- side-by-side diff
- 檔案語法高亮
- 多版本 diff 歷史瀏覽

## 快取檔案結構

- `cache/diff/<ruleId>/summary.json`
- `cache/diff/<ruleId>/commits/<commitId>.json`
- `cache/diff/<ruleId>/meta.json`

### `summary.json`

保存：

- `ruleId`
- `projectId`
- `projectName`
- `ruleName`
- `sourceBranch`
- `targetBranch`
- `targetRemoteName`
- `targetRemoteUrl`
- `summary.aheadCommits`
- `summary.changedFiles`
- `commits`

其中 `commits` 只保存：

- `id`
- `title`
- `author`
- `committedAt`
- `selectable`

### `commits/<commitId>.json`

保存：

- `commitId`
- `title`
- `files`
  - `status`
  - `path`

### `meta.json`

保存：

- `cachedAt`
- `sourceRef`
- `targetRef`
- `cacheStatus`
  可為：
  - `fresh`
  - `stale`
  - `refreshing`
  - `failed`
- `lastRefreshMessage`

## UI 流程

### 開啟差異頁

1. 前端先呼叫 `GET /api/rules/{id}/diff-cache`
2. 若有可用快取，立即顯示：
   - ahead commit 清單
   - 快取時間
   - 快取狀態
3. 若沒有可用快取：
   - 顯示 `尚未建立差異快取`
   - 提供 `抓取最新差異` 按鈕

### 手動抓取最新差異

1. 使用者按 `抓取最新差異`
2. 後端執行：
   - `fetch origin`
   - `fetch target remote`
   - 重建 `summary.json`
   - 清除舊 `commits/*.json` 快取
3. 完成後回傳新的摘要
4. UI 更新快取時間與 commit 清單

### 點擊單一 commit

1. 前端呼叫 `GET /api/rules/{id}/diff-cache/commits/{commitId}`
2. 後端流程：
   - 若該 commit 檔案清單快取存在，直接回傳
   - 若不存在，現場產生並存檔後回傳
3. UI 顯示該 commit 的異動檔案清單

### Commit-based 同步

1. 使用者在 commit 清單中勾選一個或多個 commit
2. UI 記錄 `selectedCommitIds`
3. 使用者人工確認
4. UI 呼叫同步 API，request body 帶入 `selectedCommitIds`

## API 設計

### `GET /api/rules/{id}/diff-cache`

用途：

- 讀取 ahead commit 摘要快取

回應範例：

```json
{
  "ruleId": "rule-sit",
  "cacheStatus": "fresh",
  "cachedAt": "2026-04-23T15:30:00+08:00",
  "sourceBranch": "sit",
  "targetBranch": "sit",
  "summary": {
    "aheadCommits": 3,
    "changedFiles": 12
  },
  "commits": [
    {
      "id": "abc1234",
      "title": "Fix login validation",
      "author": "SeanLiu",
      "committedAt": "2026-04-23T10:00:00+08:00",
      "selectable": true
    }
  ]
}
```

### `POST /api/rules/{id}/diff-cache/refresh`

用途：

- 強制抓取最新差異並重建快取

### `GET /api/rules/{id}/diff-cache/commits/{commitId}`

用途：

- 讀取單一 commit 的檔案清單

回應範例：

```json
{
  "ruleId": "rule-sit",
  "commitId": "abc1234",
  "cacheHit": true,
  "files": [
    {
      "status": "M",
      "path": "src/LoginService.java"
    }
  ]
}
```

### `POST /api/rules/{id}/sync`

額外請求欄位：

```json
{
  "forcePush": false,
  "reviewConfirmed": true,
  "selectedCommitIds": ["abc1234", "def5678"]
}
```

說明：

- 若 `selectedCommitIds` 有值，後端僅同步指定 commit
- `selectedCommitIds` 應依來源 branch 歷史順序傳入

## 快取生命週期

### 建立時機

- 使用者手動點 `抓取最新差異`
- 手動同步前執行 review

### 失效時機

- 規則設定被修改
- 專案 vendor repo URL 被修改
- remote baseUrl 被修改
- 同步成功後

### 清理策略

- `commits/*.json` 在摘要重建時全部刪除重建
- 若某條 rule 被刪除，對應 `cache/diff/<ruleId>` 一併刪除

## 驗收標準

1. 開啟差異頁時可看到 ahead commit 清單
2. 點擊 commit 時可看到該 commit 的檔案清單
3. 使用者可勾選 commit 作為本次同步內容
4. `抓取最新差異` 可重建摘要與 commit file list 快取
5. review-required 規則可在人工確認後以 `selectedCommitIds` 執行同步
6. 本階段不顯示實際 patch 內容
