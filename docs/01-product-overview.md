# 產品總覽

## 目標

建立一套可在離線環境中運作、且不依賴資料庫的 Git Web 工具。使用者透過網頁介面操作，本機 `Java 17` 服務負責實際執行 Git 指令。

目前需求包含兩個主軸：

1. 手動將廠商 repo 的指定 branch，同步到使用者設定的目標 remote / branch。
2. 將廠商 repo 的指定 branch 下載並對齊到本地工作目錄，不推送到其他 remote。
3. 讓多個專案可依排程自動推送到各自設定的目標 remote，或自動下載到本地。
4. 允許使用者在 UI 上隨時修改同步規則、排程、remote 與分支設定，並將結果寫回可攜式設定檔。
5. 比較來源與目標 branch 的 commit 與 tree，確認兩邊歷程及程式內容是否一致。
6. 在規則數量增加時，透過前端篩選快速縮小 Projects 列表顯示範圍。

## 已確認需求

- 執行環境只有 `Java 17`，且本機已安裝 `git`。
- 系統採離線部署，不依賴 Internet 服務，但需能連到實際要存取的 Git server。
- 廠商來源可使用 `HTTP`、`HTTPS` 或 `SSH`。
- 目標 remote 使用 `SSH`。
- 若本機指定目錄尚未存在 repo，系統需先 `clone`。
- 若本機指定目錄已存在 repo，系統需直接使用該 repo 並進行 `fetch`。
- 使用者可透過 `checkbox` 決定同步時是否加上 `git push -f`。
- rule 支援 `mode`，目前包含 `sync` 與 `download-only`。
- `sync` 模式會從來源下載到本地後，再推送到目標 remote。
- `download-only` 模式只會從來源下載並對齊本地分支，不推送到任何目標 remote，且會強制同步來源 remote tags，包含 tag 移動與刪除。
- `download-only` 規則可選填下載本地主目錄覆寫；未設定時使用全域本地主目錄。
- 每次同步按鈕只提交一筆 rule，但手動同步應以背景 job 方式執行，不阻塞 UI。
- 同步 branch 時，系統也應將來源 repo 的 tags 一併推送到目標 remote；一般同步只新增不存在的 tags，勾選 `Force Push` 時才允許移動既有 tags。
- 第一版以單人使用為主。
- 設定必須以可複製的設定檔方式保存，方便交付給其他人直接使用。
- 某些分支可被標記為僅允許手動同步，不得排程。
- 某些分支可被標記為同步前必須先檢視差異 commit 並人工確認。
- 使用者可在 UI 修改 `是否自動同步`、`remote URL`、`來源/目標分支` 等設定，且修改結果必須寫回主設定檔。
- sync 規則需提供版本一致性比對；commit hash 用於判斷 HEAD / 歷程位置，tree hash 用於判斷程式內容。
- commit hash 不同但 tree hash 相同時，應顯示「內容一致，歷程不同」，不可誤判為程式版本不同。
- Projects 列表需支援規則篩選，包含關鍵字、Remote Tab、規則模式、執行方式、最後狀態與只顯示異常。
- 篩選條件屬於本機 UI 偏好，只保存於瀏覽器，不得寫入或匯出 `config/settings.json`。

## 需求範圍

目前規格需完成以下功能：

1. 管理廠商 repo 設定。
2. 管理目標 remote 設定。
3. 管理專案底下的 rule，包含同步到目標 remote 與只下載到本地兩種模式。
4. 檢查本機目錄是否已有指定 repo。
5. 在需要時自動 clone 專案。
6. 提交單筆 rule 的同步或下載 job。
7. 顯示同步結果與執行紀錄。
8. 以單一主設定檔保存全局主目錄、remotes、projects、rules 與排程設定。
9. 在 UI 中編輯並保存 remotes、projects、rules 與排程設定。
10. 對特定 rule 啟用 review gate，要求先檢視 ahead commit、查看單一 commit 的異動檔案清單，並可從挑選 commit 直接同步。
11. 對 sync rule 執行來源與目標版本比對，顯示 commit hash、tree hash 與雙方獨有 commit 數量。
12. 在 Projects 列表篩選規則，並顯示符合筆數與全部筆數。

## 非目標

目前規格不包含以下項目：

- 多使用者帳號機制
- 資料庫
- 批次執行多筆 rule
- 完整 Git GUI
- 純瀏覽器直接執行 Git

## 核心限制

## 1. 瀏覽器不能直接操作 Git

前端 `HTML + JavaScript` 不能直接：

- 執行 `git clone`
- 執行 `git fetch`
- 執行 `git push`
- 任意讀寫本機 repo

因此本系統必須有本機 Java 服務作為 Git Bridge。

## 2. 無 DB 但仍需保存狀態

雖然不使用 DB，仍需保存以下資訊：

- 廠商 repo 設定
- 目標 remote 設定
- 專案與同步規則
- 排程設定
- review gate 設定
- 最近執行結果
- 歷史 log

其中建議區分為兩類檔案：

- 可攜式主設定檔：可直接複製給其他人
- 本機執行狀態檔：保存最近執行時間、執行中狀態等暫態資訊

## 產品邊界

本產品的定位是「針對固定同步流程的 Git Web 工具」，不是通用型 Git Desktop 替代品。現階段優先把核心路徑做穩：

- 有明確設定
- 有明確同步按鈕與同步佇列狀態
- 有明確執行結果
- 有明確 log
- review 畫面以 commit 為核心，不在本階段提供實際 patch 內容
- 可攜式設定檔可直接搬移到其他機器
- UI 修改設定後可立即回寫主設定檔
