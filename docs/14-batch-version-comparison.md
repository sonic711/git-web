# Phase 2：批次版本比對

## 目的

多個專案常具有相同同步規格，例如來源 `uat` 都要同步到 UAT Remote 的 `uat`。使用者需要一次確認所有專案的來源與目標版本，而不是逐筆點擊 `版本比對`。

## 規格分組

系統從既有 enabled sync rules 動態分組，key 為：

```text
sourceBranch + targetRemoteId + targetBranch
```

不新增額外群組設定，也不寫入 `config/settings.json`。

## 比對內容

每筆專案需取得：

- source commit hash
- target commit hash
- source tree hash
- target tree hash
- source-only / target-only commit 數量
- source HEAD tags
- target HEAD tags

Tag 定義為遠端 tag refs 中直接或經 annotated tag peel 後指向該 branch HEAD commit 的 tag。Tag 建立時間不參與判定。

## 狀態

沿用單筆比對：

- `IDENTICAL`
- `CONTENT_IDENTICAL`
- `DIFFERENT`
- `TARGET_MISSING`
- `CHECK_FAILED`

另回傳：

- `tagsIdentical=true/false/null`
- `sourceTagCheckStatus=SUCCESS/FAILED/NOT_CHECKED`
- `targetTagCheckStatus=SUCCESS/FAILED/NOT_CHECKED`
- `tagCheckMessage`

Tag 差異是輔助警示，不取代 commit / tree 判定。例如 commit 與 tree 相同但 tags 不同，狀態仍可為 `IDENTICAL`，畫面另標示 `Tag 不同`。
若 tag 查詢 timeout，commit / tree 結果仍需顯示，tag 則標示為 `無法確認`。

## 背景 job

1. UI 提交一組同步規格。
2. API 立即建立 job 並回傳 HTTP `202`。
3. job 收集所有符合的 enabled sync rules。
4. 最多 4 筆不同 repo 同時比較。
5. 每筆仍取得相同 repo lock。
6. 個別失敗回傳 `CHECK_FAILED`，不停止其他項目。
7. UI polling job，逐筆更新結果與進度。

Job 只保存在記憶體；服務重啟後可重新執行。

## 結果頁

頁面顯示：

- 規格摘要
- job 狀態
- `completed / total`
- 開始、完成時間
- 所有專案結果表格
- 只顯示不一致
- 依狀態篩選
- 單筆重新比對
- 查看差異

不一致篩選包含：

- `DIFFERENT`
- `TARGET_MISSING`
- `CHECK_FAILED`
- `tagsIdentical=false`
- `tagsIdentical=null`

`CONTENT_IDENTICAL` 的 commit 歷程不同但 tree 相同，預設歸類為內容一致；畫面仍需明確標示歷程不同。

## API

- `GET /api/version-comparison/specs`
- `POST /api/version-comparison/jobs`
- `GET /api/version-comparison/jobs/{jobId}`
- `POST /api/version-comparison/jobs/{jobId}/rules/{ruleId}`

## 驗收條件

1. 相同 source branch、target remote、target branch 的規則會分在同一規格。
2. download-only、disabled project、disabled rule 不出現在批次規格。
3. 建立 job 後 HTTP request 不等待 Git 完成。
4. 不同 repo 可並行，同 repo 不會同時執行 Git 操作。
5. UI 可看到逐筆完成進度。
6. 每筆顯示完整 commit hash 與指向 HEAD 的 tags。
7. tree 相同但 commit 不同時顯示內容一致、歷程不同。
8. commit 相同但 tags 不同時顯示 tag 警示。
9. 個別失敗不影響其他專案完成。
10. 可只顯示不一致結果。
