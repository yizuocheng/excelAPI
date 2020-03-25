package com.alanpoi.common;

import com.alanpoi.excel.exports.WorkbookManager;
import com.alanpoi.excel.exports.handle.ExportHandle;
import com.alanpoi.common.utils.ApplicationUtil;
import com.alanpoi.excel.imports.ExcelInitConfig;
import com.alanpoi.excel.imports.ExcelParser;
import com.alanpoi.excel.imports.handle.ExcelWorkbookManage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;


/**
 * 初始化配置
 *
 * @author zhuoxun.peng
 * @since 2020-2-26
 */
@Configuration
public class InitConfig {

    @Bean
    public ExcelWorkbookManage getExcelWorkbookManage() {
        return new ExcelWorkbookManage();
    }

    @Bean
    public WorkbookManager getWorkbookManage() {
        return new WorkbookManager();
    }

    @Bean
    public ExcelInitConfig getExcelHelper() {
        return new ExcelInitConfig();
    }

    @Bean
    public ApplicationUtil getApplicationUtil() {
        return new ApplicationUtil();
    }

    @Bean
    public ExportHandle getExportHandle() {
        return new ExportHandle();
    }

    @Bean
    public ExcelParser getExcelParser(ExcelInitConfig excelInitConfig,
                                      ExcelWorkbookManage excelWorkbookManage,
                                      StringRedisTemplate redisTemplate,
                                      ExecutorTools executorTools) {
        return new ExcelParser(excelInitConfig, excelWorkbookManage, redisTemplate, executorTools);
    }


}