# Phase 2：Projects 規則篩選

## 目的

當 Projects 底下累積大量專案與規則時，主畫面會變得過長，使用者難以快速找到要同步、檢查或處理失敗的規則。

本功能在既有專案摺疊功能之外，提供一組全域規則篩選列。第一版只改變前端顯示，不修改 Git 流程、排程設定或後端資料模型。

## 第一版範圍

提供：

- 關鍵字搜尋
- Remote Tab 篩選
- 執行方式篩選
- 最後狀態篩選
- `只顯示異常`
- 清除篩選
- 符合筆數摘要
- 將篩選偏好保存於瀏覽器

不提供：

- 後端分頁或查詢 API
- 將篩選條件寫入主設定檔
- 跨瀏覽器或跨電腦同步偏好
- 多組自訂常用檢視
- 使用篩選結果批次同步

## 篩選欄位

### 關鍵字

採不區分大小寫的部分比對，搜尋：

- project name
- rule name
- source branch
- target branch
- Remote Tab name
- target repo name

空白關鍵字代表不限制。

### Remote Tab

選項來自目前啟用與停用的 Remote Tab，並提供 `全部`。

- 選擇全部時，顯示所有符合其他條件的規則。
- 選擇特定 Remote Tab 時，只顯示 `targetRemoteId` 相同的 sync rule。
- `download-only` 沒有 target remote，選擇特定 Remote Tab 時不顯示。

### 執行方式

選項：

- 全部
- 自動同步
- 僅手動

判定：

- 自動同步：`schedule.enabled=true` 且 `manualOnly=false`
- 僅手動：`manualOnly=true` 或 `schedule.enabled=false`

此篩選描述目前是否會由 scheduler 自動執行，不限制使用者能否按下手動同步。

### 最後狀態

選項：

- 全部
- 尚未執行
- 排隊中
- 執行中
- 成功
- 失敗
- 中斷

狀態來源優先順序：

1. 目前背景 job 狀態 `queued / running`
2. runtime state 的 `lastStatus`
3. 沒有 runtime state 時為尚未執行

### 只顯示異常

第一版將異常定義為最後狀態 `failed` 或 `interrupted`。

- 啟用後只顯示失敗或中斷規則。
- 啟用時忽略並停用 `最後狀態` 下拉選單，避免兩個狀態條件互相衝突。
- `queued`、`running` 與尚未執行不視為異常。

## 條件組合

所有已設定條件採 AND：

```text
keyword matched
AND remote matched
AND execution mode matched
AND status matched
```

若啟用 `只顯示異常`，status 條件固定為 failed 或 interrupted。

## 專案與摺疊行為

- project 沒有符合規則時，隱藏整個 project。
- project 有符合規則時，只 render 符合的 rules。
- 有任何篩選條件時，符合的 project 規則需可見，不受手動收合狀態阻擋。
- 篩選期間停用專案收合按鈕，避免改寫原本收合狀態。
- 篩選不得改寫使用者原本的收合狀態。
- 清除篩選後，恢復篩選前的收合狀態。
- 沒有結果時顯示「沒有符合條件的規則」。

## 結果摘要

篩選列顯示：

```text
符合 8 / 全部 56 條規則
```

- `符合` 是套用所有條件後的 rule 數量。
- `全部` 是目前載入設定中的 rule 總數，不受 project 收合影響。

## 保存策略

篩選是個人操作偏好，不是可攜式業務設定。

- 使用瀏覽器 `localStorage`。
- 建議 key：`git-web.rule-filters.v1`。
- 保存 keyword、remoteId、executionMode、status 與 abnormalOnly。
- 頁面重新整理或 Java 服務重啟後恢復。
- 不寫入 `config/settings.json`。
- 不寫入 `state/runtime-state.json`。
- 不包含在設定檔匯出內容。
- 匯入設定檔不清除篩選條件。
- 若保存的 Remote Tab 已不存在，自動改回 `全部`。
- localStorage 無法解析或版本不相容時，忽略舊值並使用預設條件。

## 資料刷新

以下事件發生後，都需使用相同條件重新計算：

- UI 定時刷新 projects / runtime state
- 手動重新整理
- 新增或編輯 project
- 新增、編輯或刪除 rule
- 新增、編輯或刪除 Remote Tab
- 匯入設定檔
- 同步 job 狀態改變

重新計算不得清除使用者已輸入的關鍵字或其他篩選條件。

## 技術設計

第一版採純前端實作：

1. API 照常回傳所有 projects 與 rules。
2. 前端建立單一 `ruleFilters` state。
3. render Projects 前先計算每個 project 的符合 rules。
4. 只 render 至少有一條符合 rule 的 project。
5. 更新篩選條件時同步寫入 localStorage 並重新 render。

此設計適合目前單人、設定檔規模的使用情境。若未來規則數量大到前端載入或 render 明顯變慢，再新增後端查詢、分頁或虛擬列表。

## 驗收條件

1. 輸入關鍵字後，只顯示任一搜尋欄位符合的規則。
2. 選取 Remote Tab 後，不顯示其他 remote 與 download-only 規則。
3. 選取自動同步或僅手動後，結果符合排程設定。
4. 狀態篩選可正確區分 queued、running、success、failed、interrupted 與尚未執行。
5. `只顯示異常` 只顯示 failed 或 interrupted 規則。
6. 多個條件同時使用時採 AND。
7. 沒有符合結果時顯示空狀態。
8. 自動刷新後篩選條件與結果仍正確。
9. 重新整理頁面後可恢復篩選條件。
10. 匯出設定檔不包含篩選條件。
11. 清除篩選後顯示全部規則並恢復原本專案收合狀態。
