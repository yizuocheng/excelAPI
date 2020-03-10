package com.qizhidao.alanpoi.excel.handle;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import com.qizhidao.alanpoi.common.AbstractExcelService;
import com.qizhidao.alanpoi.common.ExecutorTools;
import com.qizhidao.alanpoi.common.ResponseEnum;
import com.qizhidao.alanpoi.excel.ApplicationUtil;
import com.qizhidao.alanpoi.excel.ErrorFile;
import com.qizhidao.alanpoi.excel.ExcelImportRes;
import com.qizhidao.alanpoi.excel.ExcelSheetData;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.CollectionUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;


/**
 * excel核心处理类
 *
 * @author zhuoxun.peng
 * @since 2020-2-26
 */
@Slf4j
public class ExcelHandle {
    public ExcelWorkbookManage excelWorkbookManage;

    public ExecutorTools executorTools;

    public AbstractExcelService excelService;

    public StringRedisTemplate redisTemplate;

    @Value("${server.port:5577}")
    public String port;

    @Value("${excel.tmp_path:/tmp/ips-product/import/}")
    public String tmpPath;

    @Value("${excel.download_path:/product/common/download/}")
    public String downloadPath;

    public ExcelImportRes process(String workbookId, List<ExcelSheetData> sheetDataList, Class<? extends ExcelConsumeInterface> c, String fileName, long frameId) {
        log.info("ExcelHandle.process ");
        int total = sheetDataList.get(0).getData().size();
        ExcelImportRes excelImportRes = new ExcelImportRes();
        ExcelConsumeInterface consumeInterface = ApplicationUtil.getBean(c);
        consumeInterface.validData(workbookId, sheetDataList);
        ExcelError excelError = excelWorkbookManage.getExcelError(workbookId);
        int rowStart = sheetDataList.get(0).getRowStart();
        CompletableFuture<String> completableFuture = CompletableFuture.supplyAsync(() -> {
            if (excelError != null && !CollectionUtils.isEmpty(excelError.getSheetErrors())) {
                sheetDataList.forEach(e -> {
                    List<Object> errorDataList = new ArrayList<>();
                    e.getData().forEach(vo -> {
                        JSONObject jsonObject = JSONObject.parseObject(JSONObject.toJSONString(vo));
                        excelError.getSheetErrors().forEach(er -> {
                            er.getRowErrors().forEach(re -> {
                                if (re.getRowIndex() == jsonObject.getInteger("rowIndex")) {
                                    errorDataList.add(vo);
                                }
                            });
                        });
                    });
                    e.getData().removeAll(errorDataList);
                });
            }
            log.info("import success:{},error:{}", sheetDataList.size(), total - sheetDataList.size());
            consumeInterface.end(sheetDataList);
            return null;

        }).supplyAsync(() -> {
            if (excelError != null &&
                    !CollectionUtils.isEmpty(excelError.getSheetErrors())) {
                String newFileName = "";
                if (StringUtils.isNotEmpty(fileName)) {
                    String filePre = fileName.substring(0, fileName.lastIndexOf("."));
                    String suffix = fileName.substring(fileName.lastIndexOf("."));
                    StringBuffer sb = new StringBuffer(filePre);
                    newFileName = sb.append("_").append(new Date().getTime()).append(suffix).toString();
                } else {
                    newFileName = new Date().getTime() + ".xlsx";
                }
                writeError(workbookId, excelError, excelWorkbookManage.getWorkbook(workbookId), newFileName, rowStart);
                return newFileName;
            }
            return null;
        });
        completableFuture.join();
        try {
            if (StringUtils.isNotEmpty(completableFuture.get())) {
                consumeInterface.error(excelError);
                excelImportRes.setStatus(ResponseEnum.IMPORT_FILE_DATA_EXP.status());
                excelImportRes.setDownErrorUrl(downloadPath + workbookId);
                //
                updateLogToFrame(frameId, -1, null, excelImportRes.getDownErrorUrl());
            } else {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(3072);
                excelWorkbookManage.getWorkbook(workbookId).write(bytes);
                //
                updateLogToFrame(frameId, 1, bytes.toByteArray(), null);
            }
            excelImportRes.setMessage(String.format(ResponseEnum.IMPORT_FILE_DATA_EXP.message(), sheetDataList.get(0).getData().size(), total - sheetDataList.get(0).getData().size()));
        } catch (Exception e) {
            log.error("", e);
        }
        //释放工作空间
        excelWorkbookManage.removeWorkbook(workbookId);
        return excelImportRes;

    }

    private void writeError(String workbookId, ExcelError excelError, Workbook workbook, String fileName, int rowStart) {
        executorTools.getExecutor().execute(new Runnable() {
            @Override
            public void run() {
                ErrorFile errorFile = new ErrorFile(workbookId, ApplicationUtil.getInetAddress().getHostAddress(), tmpPath, fileName);
                redisTemplate.opsForValue().set("product:import:" + workbookId, JSON.toJSONString(errorFile), 15, TimeUnit.DAYS);
            }
        });
        File file = new File(tmpPath);
        if (!file.exists()) {
            file.mkdirs();
        }
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(tmpPath + fileName);
            List<ExcelError.SheetError> sheetErrorList = excelError.getSheetErrors();
            sheetErrorList.forEach(sheetError -> {
                Font font = workbook.createFont();
                CellStyle cellStyle = workbook.createCellStyle();
                //设置红色
                cellStyle.setFont(font);
                font.setColor(Font.COLOR_RED);
                Sheet sheet = workbook.getSheetAt(sheetError.getIndex());
                Row head = sheet.getRow(rowStart - 1);
                int colNum = head.getLastCellNum();
                Cell headCell = head.createCell(colNum);
                headCell.setCellValue("错误信息");
                headCell.setCellStyle(cellStyle);
                List<RowError> rowErrors = sheetError.getRowErrors();
                rowErrors.forEach(rowError -> {
                    Row row = sheet.getRow(rowError.getRowIndex());
                    Cell cell = row.createCell(colNum);
                    cell.setCellValue(rowError.getErrorMsg());
                    cell.setCellStyle(cellStyle);
                });
            });
            try {
                workbook.write(fileOutputStream);
            } catch (Exception e) {
                log.error("", e);
            } finally {
                try {
                    if (fileOutputStream != null) {
                        fileOutputStream.flush();
                        fileOutputStream.close();
                    }
                } catch (IOException io) {
                    log.error("", io);
                }
            }
        } catch (Exception e1) {
            log.error("", e1);
        }

    }

    public void addErrorInfo(String workbookId, List<RowError> rowErrors) {
        excelWorkbookManage.addErrorInfo(workbookId, 0, rowErrors);

    }

    public void addErrorInfo(String workbookId, int sheetIndex, RowError rowError) {
        excelWorkbookManage.addErrorInfo(workbookId, sheetIndex, rowError);
    }

    public void addErrorInfo(String workbookId, int sheetIndex, List<RowError> rowErrors) {
        excelWorkbookManage.addErrorInfo(workbookId, sheetIndex, rowErrors);
    }

    /**
     * 更新日志到框架导入导出
     *
     * @param frameId
     * @param status
     * @param bytes
     */
    public void updateLogToFrame(long frameId, int status, byte[] bytes, String filePath) {
        executorTools.getExecutor().execute(new Runnable() {
            @Override
            public void run() {
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("id", frameId);
                jsonObject.put("status", status);

                if (StringUtils.isNotEmpty(filePath)) {
                    jsonObject.put("fileUrl", filePath);
                }
                if (bytes != null) {
                    excelService.uploadFileToFrame(jsonObject.toJSONString(), bytes);
                } else {
                    excelService.uploadFileToFrame(jsonObject.toJSONString());
                }

            }
        });

    }
}