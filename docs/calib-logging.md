# 校正逐筆紀錄（暫時性診斷，內部調校用）

## 為什麼需要

留一輪交叉驗證誤差 2422 px（2026-11-23 實機，三輪「坐著」校正）顯示各輪校正
彼此對不起來，且手機稍微晃動就失準；但校正樣本只存在記憶體、存檔只留模型，
事後無法分析是哪一輪、哪個點、在什麼光線與裝置角度下跑掉。本紀錄補上這個缺口。

## 格式

- 樣本 CSV：id 首碼 `calib-`（例 `calib-sitting-2026-11-23_22-40-00-000-0`），
  `rows` = 樣本筆數。表頭（共 51 欄）：
  `pass,target,idx,tMs,targetX,targetY,kept,luxLog,devPitch,devRoll,f0,...,f24,wallMs,luxRaw,leftOpen,rightOpen,eyeMeanLum,eyeContrast,specularRatio,rawPitch,rawRoll,rawYaw,rvX,rvY,rvZ,rvW,predX,predY`。
  每點「全部」原始樣本都記（含被離群值剔除的，`kept` 欄位標示 1／0）；
  `tMs` 相對本次校正首筆樣本；`luxLog` 為 log10(lux)，NaN 記空欄；
  `devPitch`／`devRoll` 為裝置角度（弧度，經平滑），NaN 記空欄；`f0..f24` 為 25 維特徵。
  診斷擴充欄：`wallMs` 絕對時間戳（毫秒）；`luxRaw` 原始 lux；`leftOpen`／`rightOpen`
  睜眼程度；`eyeMeanLum`／`eyeContrast`／`specularRatio` 眼部影像度量；
  `rawPitch`／`rawRoll`／`rawYaw` 未平滑原始 orientation；`rvX`／`rvY`／`rvZ`／`rvW`
  旋轉向量原始分量；`predX`／`predY` 以新模型對該樣本的預測座標（與報告頁誤差
  同一算法）。非有限值一律記空欄。
  記不到的數字：臉部信心分數（MediaPipe 沒有提供）與生陀螺儀角速度（管線刻意
  不用，見 DeviceOrientationTracker 說明）——這兩項管線裡不存在，不得假造。
  安定等待期（settle）內跳過的影格不計入丟棄數。
- 摘要 JSON：id 為樣本 id 加 `-sum`，`rows=0`，`csv` 欄位放 JSON，內容為報告頁
  同一批數字（訓練集／留一法／留一輪誤差、最差點、逐點誤差、光線範圍、模型光跨度、
  筆數與留用筆數），非有限值記為 null；另含 `refused`／`separationRatio`／
  `separationThreshold`／`separation`／`separationMin`（有效性檢查，見
  docs/calibration-gate.md）與 `droppedFaceLost`／`droppedBlink`（每目標點採樣時
  因臉遺失／眨眼閉眼丟棄的影格數，依 pointIndex 順序）。
- 使用紀錄 gaze-（同一張表，`GazeLog`）：17 欄之外另記 `wallMs,mode,event,devPitch,
  devRoll,rawPitch,rawRoll,rvX,rvY,rvZ,rvW,leftOpen,rightOpen,eyeMeanLum`（共 30 欄）；
  `mode` 為當時操作模式，`event` 為動作事件（tap／double_tap／
  long_press／swipe／swipe_start／edge_back／edge_home／edge_scroll_up／
  edge_scroll_down／mode_change:XXX；動作派發時掛上，下一筆成功寫入的列才帶上
  並清除，節流丟棄時保留不遺失），臉遺失列的裝置角度取服務端平滑值。

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
