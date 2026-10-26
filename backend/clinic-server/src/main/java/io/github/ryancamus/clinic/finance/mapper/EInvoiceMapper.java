package io.github.ryancamus.clinic.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.ryancamus.clinic.finance.domain.entity.EInvoice;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface EInvoiceMapper extends BaseMapper<EInvoice> {

    @Select("SELECT * FROM e_invoice WHERE biz_key = #{bizKey}")
    EInvoice selectByBizKey(@Param("bizKey") String bizKey);

    /** 收费单当前有效的正票：已开出、且没有被红票冲抵 */
    @Select("""
            SELECT b.* FROM e_invoice b
            WHERE b.charge_order_id = #{chargeOrderId} AND b.invoice_type = 1 AND b.status IN (0, 1)
              AND NOT EXISTS (SELECT 1 FROM e_invoice r WHERE r.original_invoice_id = b.id)
            ORDER BY b.id DESC LIMIT 1
            """)
    EInvoice selectActiveBlue(@Param("chargeOrderId") Long chargeOrderId);

    @Update("""
            UPDATE e_invoice SET status = 1, invoice_no = #{invoiceNo}, pdf_url = #{pdfUrl}, issue_time = #{at},
                   version = version + 1, update_time = NOW()
            WHERE id = #{id} AND status <> 1
            """)
    int markIssued(@Param("id") Long id, @Param("invoiceNo") String invoiceNo, @Param("pdfUrl") String pdfUrl,
                   @Param("at") LocalDateTime at);

    /** 财政电子票据平台未接入：财务在平台网页或票据系统开具后，登记票号与登记人 */
    @Update("""
            UPDATE e_invoice SET status = 1, invoice_no = #{invoiceNo}, issue_time = #{at}, issue_mode = 2,
                   issued_by = #{operatorId}, version = version + 1, update_time = NOW()
            WHERE id = #{id} AND status = 0
            """)
    int registerManual(@Param("id") Long id, @Param("invoiceNo") String invoiceNo, @Param("operatorId") Long operatorId,
                       @Param("at") LocalDateTime at);
}
