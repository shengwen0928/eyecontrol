# 校正逐筆紀錄（暫時性診斷，內部調校用）

## 為什麼需要

留一輪交叉驗證誤差 2422 px（2026-11-23 實機，三輪「坐著」校正）顯示各輪校正
彼此對不起來，且手機稍微晃動就失準；但校正樣本只存在記憶體、存檔只留模型，
事後無法分析是哪一輪、哪個點、在什麼光線與裝置角度下跑掉。本紀錄補上這個缺口。

## 格式

- 樣本 CSV：id 首碼 `calib-`（例 `calib-sitting-2026-11-23_22-40-00-000-0`），
  `rows` = 樣本筆數。表頭：
  `pass,target,idx,tMs,targetX,targetY,kept,luxLog,devPitch,devRoll,f0,...,f24`。
  每點「全部」原始樣本都記（含被離群值剔除的，`kept` 欄位標示 1／0）；
  `tMs` 相對本次校正首筆樣本；`luxLog` 為 log10(lux)，NaN 記空欄；
  `devPitch`／`devRoll` 為裝置角度（弧度），NaN 記空欄；`f0..f24` 為 25 維特徵。
- 摘要 JSON：id 為樣本 id 加 `-sum`，`rows=0`，`csv` 欄位放 JSON，內容為報告頁
  同一批數字（訓練集／留一法／留一輪誤差、最差點、逐點誤差、光線範圍、模型光跨度、
  筆數與留用筆數），非有限值記為 null。

## 閘門（與游標紀錄同一語意）

- 診斷紀錄（`diagnosticLogging`）與遠端監控（`remoteMonitor`）兩開關皆開才上傳；
  存檔成功後只傳一次（重試儲存不重傳）；Play 版由 `GazeLog` 內的 flavor 守衛
  （`BuildConfig.REMOTE_MONITOR_ENABLED`）擋下，不發出任何請求。
- 上傳走既有 `GazeLog` 上傳執行緒與 Supabase 端點（同一張 `gaze_logs` 表），
  失敗只寫 Logcat、不重試、不打擾使用者。本機不留檔。

## 監看

`gaze-monitor-watch` 排程另抓 `calib-` 列：逐輪樣本數、留用率、每輪平均亮度、
每輪特徵均值漂移、摘要數字；在 09:00–21:30 交付，時段外寫 pending.md。

## 移除（上架前整段刪除，見 REMOVAL_CHECKLIST.md）

`CalibLog.kt`、`CalibLogTest.kt`、本文件、`CalibrationActivity` 內的
`calibRows`／`pointSampleTimes`／`calibT0Ms`／`calibUploaded`／
`calibUploadCounter`／`uploadCalibLog`、`GazeLog.uploadCalibration`、
`gaze-monitor-watch` 的 calib 分析段、Supabase `gaze-monitor` 專案。
