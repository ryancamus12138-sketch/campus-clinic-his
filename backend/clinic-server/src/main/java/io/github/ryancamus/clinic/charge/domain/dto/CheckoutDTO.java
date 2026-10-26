package io.github.ryancamus.clinic.charge.domain.dto;

import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "收费窗口结算：医保结算 + 一种或多种方式收取自付")
public record CheckoutDTO(
        @NotEmpty @Size(max = 4) @Valid @Schema(description = "收款方式，金额之和等于应收") List<Tender> tenders,
        @Pattern(regexp = "0[123]") @Schema(description = "就诊凭证类型：01 医保电子凭证 02 居民身份证 03 社会保障卡；"
                + "收费单有医保报销时使用，不填按 02") String certType,
        @Size(max = 128) @Schema(description = "凭证编号：扫医保码或读卡所得；不填由系统按患者档案读取") String certNo,
        @Valid @Schema(description = "医保结算平台未接入时必填：收费员在医保结算终端完成结算后登记的结果") InsuranceRegistration insurance) {

    public CheckoutDTO(List<Tender> tenders, String certType, String certNo) {
        this(tenders, certType, certNo, null);
    }

    /**
     * 在医保结算终端（医保部门提供的结算客户端）完成结算后，登记到本系统的结果。
     *
     * @param setlId  终端显示的结算单号（医保结算流水号）
     * @param fundPay 终端显示的统筹（基金）支付金额，必须与划价时的统筹金额一致
     */
    public record InsuranceRegistration(
            @NotNull @Pattern(regexp = "[A-Za-z0-9-]{4,64}") @Schema(description = "医保结算单号") String setlId,
            @NotNull @Schema(description = "统筹支付金额", example = "16.00") Money fundPay) {
    }

    public enum Method {
        /** 现金 */
        CASH,
        /** 校园卡：刷卡或输入卡号，由学校一卡通扣款 */
        CAMPUS_CARD,
        /** 银行卡：在 POS 机上刷卡，系统登记 POS 参考号 */
        BANK_CARD,
        /** 付款码：扫患者的微信或支付宝付款码 */
        AUTH_CODE
    }

    public record Tender(
            @NotNull Method method,
            @NotNull @Schema(description = "本方式收取的金额", example = "20.00") Money amount,
            @Schema(description = "现金实收（找零用），仅现金") Money received,
            @Size(max = 20) @Schema(description = "校园卡号，仅校园卡") String cardNo,
            @Size(max = 32) @Schema(description = "POS 参考号，仅银行卡") String reference,
            @Size(max = 32) @Schema(description = "付款码，仅付款码") String authCode) {

        public static Tender cash(Money amount, Money received) {
            return new Tender(Method.CASH, amount, received, null, null, null);
        }

        public static Tender campusCard(Money amount, String cardNo) {
            return new Tender(Method.CAMPUS_CARD, amount, null, cardNo, null, null);
        }

        public static Tender bankCard(Money amount, String reference) {
            return new Tender(Method.BANK_CARD, amount, null, null, reference, null);
        }

        public static Tender authCode(Money amount, String authCode) {
            return new Tender(Method.AUTH_CODE, amount, null, null, null, authCode);
        }
    }
}
