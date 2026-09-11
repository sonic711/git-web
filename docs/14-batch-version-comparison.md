# Phase 2：批次版本比對

## 目的

多個專案常具有相同同步規格，例如來源 `uat` 都要同步到 UAT Remote 的 `uat`。使用者需要一次確認所有專案的來源與目標版本，而不是逐筆點擊 `版本比對`。

## 規格分組

系統從既有 enabled sync rules 動態分組，key 為：

```text
sourceBranch + targetRemoteId + targetBranch
```

不新增額外群組設定，也不寫入 `config/settings.json`。

## 專案選取

使用者選擇同步規格後，頁面需列出該規格下所有可比對的 enabled projects，並預設全選。

- 使用者可逐筆勾選或取消勾選 project。
- 提供「全選」與「清除」操作，並顯示已選專案數量。
- 建立 job 時，只比對被勾選 project 底下、且符合所選同步規格的 enabled sync rules。
- 至少須選取一個 project 才能開始比對。
- 此選取只屬於本次 job，不得寫入 `config/settings.json`，也不應影響其他批次規格。
- API 需在建立 job 時重新驗證 project 仍符合所選規格；已停用、已刪除或規格已變更的 project 不得被納入。

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

1. UI 選擇一組同步規格與一個以上 project。
2. API 立即建立 job 並回傳 HTTP `202`。
3. job 收集被選取 project 中所有符合的 enabled sync rules。
4. 最多 4 筆不同 repo 同時比較。
5. 每筆仍取得相同 repo lock。
6. 個別失敗回傳 `CHECK_FAILED`，不停止其他項目。
7. UI polling job，逐筆更新結果與進度。

Job 只保存在記憶體；服務重啟後可重新執行。

## 結果頁

頁面顯示：

- 規格摘要
- 本次選取的專案數量
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

1. 相同 source branch、target remote、target branch 的規則會分在同一規格，且規格回應需帶回可選專案清單。
2. download-only、disabled project、disabled rule 不出現在批次規格。
3. 建立 job 後 HTTP request 不等待 Git 完成。
4. 不同 repo 可並行，同 repo 不會同時執行 Git 操作。
5. UI 可看到逐筆完成進度。
6. 每筆顯示完整 commit hash 與指向 HEAD 的 tags。
7. tree 相同但 commit 不同時顯示內容一致、歷程不同。
8. commit 相同但 tags 不同時顯示 tag 警示。
9. 個別失敗不影響其他專案完成。
10. 可只顯示不一致結果。
11. 選取特定 projects 後，job 的總筆數與結果只包含被選取 projects 的符合規則。
12. 未選取任何 project 時，不得建立 job。
