# HTTPS 憑證設定

## 目的

內部 Git 使用自簽伺服器憑證或公司 CA 時，提供匯入公開憑證與指定 HTTPS 網域／URL 前綴的功能，處理 `SSL certificate problem: self signed certificate`，保留憑證鏈及主機名稱驗證。

本功能用於 Git 存取來源及目標 Repo，不是為本工具的 localhost 網頁啟用 HTTPS，也不是用戶端憑證或私鑰管理功能。

## 操作

側欄「HTTPS 憑證」或設定頁的「HTTPS 憑證」分頁開啟 `/#certificates`。

1. 按「匯入憑證」。
2. 填寫名稱及 HTTPS 網域／URL 前綴。
3. 選擇 PEM／DER 的 `.pem`、`.crt` 或 `.cer` 公開憑證，可包含 PEM 憑證鏈。每檔最多 256 KB。
4. 儲存後，下一次 clone、fetch、pull、push、tag 查詢或版本比對即生效，手動與排程共用相同設定。

每筆顯示名稱、前綴、啟用狀態、有效期間、憑證數量與憑證組 SHA-256，並提供編輯、啟用／停用及刪除。編輯時不選新檔案即保留原憑證；刪除需確認。

私鑰、PFX／P12 及非 X.509 憑證不作為此功能的輸入。過期或尚未生效的憑證可以管理及替換，列表會標示狀態，Git 仍依 TLS 驗證結果決定是否允許連線。

## 前綴比對

| 輸入 | 套用範圍 |
| --- | --- |
| `git.example.com` | 正規化成 `https://git.example.com/`，套用該 HTTPS 主機的預設 443 連接埠 |
| `https://git.example.com:8443/` | 僅套用指定主機的 8443 連接埠 |
| `https://git.example.com/team/` | 僅套用該主機下 `team/` 的路徑範圍 |

主機、連接埠及路徑遵循 Git 的 URL 設定比對規則，路徑在 `/` 邊界比對，不採任意字串的 startsWith；不會把 `git.example.com.evil/` 或其他子網域當成同一網域。前綴不可含帳密、萬用字元、query 或 fragment。

相同前綴的多筆啟用憑證會合併成一個 CA bundle，可同時匯入根 CA 與中繼 CA。前綴重疊時由 Git 選擇較精確的範圍。

受管理的前綴設定 `followRedirects=false`，避免重導向沿用同一信任設定到其他主機或路徑。若 Git server 回傳重導向，請將 Repo URL 改成實際的最終位址並設定相符前綴。

## 設定與匯入／匯出

主設定檔新增 `trustedCertificates`，舊設定缺漏時視為空陣列：

```json
{
  "trustedCertificates": [
    {
      "id": "cert-example",
      "name": "Internal Git CA",
      "urlPrefix": "https://git.example.com/team/",
      "enabled": true,
      "pem": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----\n"
    }
  ]
}
```

`pem` 必須為實際的有效 X.509 公開憑證；上例只示意結構。資料由 UI 匯入後驗證並轉成標準 PEM。

公開憑證、名稱、前綴及啟用狀態會隨主設定檔匯出／匯入，適用既有的設定檔切換流程。匯入替換整份設定，因此憑證清單也以匯入檔內容為準。本機路徑仍依既有規則排除及保留。

執行 Git 時按 PEM 內容雜湊產生 `config/certificates/<sha256>.pem`，與 `config/settings.json` 一樣不追蹤到 Git。快取檔不可作為設定來源；憑證變更會使用新的雜湊檔，停用或刪除後後續操作不再帶入該前綴。已啟動的 Git 子程序保留啟動時取得的設定，同一同步工作後續啟動的新 Git 子程序則可讀取更新設定。舊快取保留，避免刪除在途 Git 正在使用的檔案。

## 執行策略

每個由本工具啟動的 Git 程序，以 `git -c` 傳入 URL 範圍內的設定：

```text
http.<https-url-prefix>.sslCAInfo=<absolute-ca-bundle-path>
http.<https-url-prefix>.sslVerify=true
http.<https-url-prefix>.schannelUseSSLCAInfo=true
http.<https-url-prefix>.followRedirects=false
```

不執行 `git config --global`、不匯入作業系統的信任儲存區，也不寫入 Repo 的 `.git/config`。沒有對應前綴的 HTTPS URL 與 SSH 連線維持原本 Git 行為。

Windows Git 使用 Schannel 時，`schannelUseSSLCAInfo=true` 讓它使用匯入的 CA bundle；此能力需 Git 所搭配的 libcurl 支援（官方文件列出的 Schannel CA bundle 支援自 libcurl 7.60.0 起）。範圍內會使用該 bundle，請匯入完整的必要信任鏈。

若執行服務的環境另設 `GIT_SSL_CAINFO`、`GIT_SSL_CAPATH` 或 `GIT_SSL_NO_VERIFY`，Git 的環境設定可能覆蓋 URL 設定；應先清除衝突的環境設定。匯入憑證不會解決網址與憑證 SAN 不符、憑證過期、HTTPS 帳號權限或網路連線問題。

依據：[Git 官方 git-config 文件](https://git-scm.com/docs/git-config#Documentation/git-config.txt-httpsslCAInfo)、[URL 範圍與 Schannel 設定](https://git-scm.com/docs/git-config)。

## API

- `GET /api/system/certificates`：回傳 metadata、憑證數量與有效期間，不回傳 PEM。
- `POST /api/system/certificates`：接收 `name`、`urlPrefix`、`contentBase64`、`enabled`，驗證後回傳 `201`。
- `PUT /api/system/certificates/{id}`：修改名稱、前綴、啟用狀態；可選擇性帶 `contentBase64` 替換憑證。
- `DELETE /api/system/certificates/{id}`：移除信任設定。

無效憑證、私鑰、超過檔案限制、非 HTTPS 前綴或找不到的 id，回傳 `400 INVALID_REQUEST`，並保留原設定。

## 驗證

`tests/HttpsCertificateTest.java` 透過 Java keytool 產生暫存自簽憑證及本機 HTTPS 伺服器，使用真正的 `git ls-remote` 驗證匯入前失敗、匯入後成功、未符合前綴失敗、主機名稱不符失敗、重導向受限、設定 CRUD、PEM／DER、同前綴合併、匯入匯出與重啟。

UI 回歸測試覆蓋匯入、編輯時保留原檔、停用、刪除、無效檔案的表單錯誤、導覽與響應式布局。Windows Schannel 參數由自動測試核對；本次實際 TLS 測試環境為 macOS，尚未實機驗證 Windows Schannel。
