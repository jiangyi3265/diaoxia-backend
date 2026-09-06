package com.ruoyi.web.domain.xy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import com.ruoyi.common.utils.poi.ExcelUtil;

class XyMemberExportRowTest
{
    @Test
    void identifiersStayAsTextAndFormulaLikeNicknameIsEscaped() throws Exception
    {
        XyMemberExportRow row = new XyMemberExportRow();
        row.setMemberId("9223372036854775807");
        row.setNickname("=1+1");
        row.setMobile("13900000000");
        row.setInviteCode("001234567890");
        row.setCardNo("00012345678901234567890");
        row.setStartDate("2026-09-05");
        row.setExpireDate("2026-10-05");
        row.setCardStatus("有效");
        row.setMemberStatus("正常");
        row.setInviterNickname("邀请人");
        row.setInviterInviteCode("000000000001");
        row.setCreateTime("2026-09-05 12:34:56");

        MockHttpServletResponse response = new MockHttpServletResponse();
        new ExcelUtil<XyMemberExportRow>(XyMemberExportRow.class)
                .exportExcel(response, Collections.singletonList(row), "会员名单");
        byte[] bytes = response.getContentAsByteArray();
        assertTrue(bytes.length > 2);
        assertEquals((byte) 'P', bytes[0]);
        assertEquals((byte) 'K', bytes[1]);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes)))
        {
            Row header = workbook.getSheetAt(0).getRow(0);
            Row data = workbook.getSheetAt(0).getRow(1);
            String[] expectedHeaders = { "会员ID", "会员昵称", "手机号", "邀请码", "会员卡号", "生效日期",
                    "到期日期", "会员卡状态", "账号状态", "邀请人昵称", "邀请人邀请码", "注册时间" };
            assertEquals(expectedHeaders.length, header.getPhysicalNumberOfCells());
            for (int index = 0; index < expectedHeaders.length; index++)
            {
                assertEquals(expectedHeaders[index], header.getCell(index).getStringCellValue());
            }
            assertTextCell(data, 0, "9223372036854775807");
            assertTextCell(data, 2, "13900000000");
            assertTextCell(data, 3, "001234567890");
            assertTextCell(data, 4, "00012345678901234567890");
            assertTextCell(data, 10, "000000000001");
            assertEquals(CellType.STRING, data.getCell(1).getCellType());
            assertEquals("\t=1+1", data.getCell(1).getStringCellValue());
        }
    }

    @Test
    void emptyResultStillProducesAValidWorkbook() throws Exception
    {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ExcelUtil<XyMemberExportRow>(XyMemberExportRow.class)
                .exportExcel(response, Collections.emptyList(), "会员名单");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray())))
        {
            assertEquals(1, workbook.getSheetAt(0).getPhysicalNumberOfRows());
            assertEquals("会员ID", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
        }
    }

    private void assertTextCell(Row row, int index, String value)
    {
        assertEquals(CellType.STRING, row.getCell(index).getCellType());
        assertEquals("@", row.getCell(index).getCellStyle().getDataFormatString());
        assertEquals(value, row.getCell(index).getStringCellValue());
    }
}
