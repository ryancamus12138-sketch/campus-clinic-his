package io.github.ryancamus.clinic.pharmacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.ryancamus.clinic.pharmacy.domain.entity.DrugTraceCode;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface DrugTraceCodeMapper extends BaseMapper<DrugTraceCode> {

    @Update("""
            UPDATE drug_trace_code SET status = 4, version = version + 1, update_time = NOW()
            WHERE id = #{id} AND status <> 4
            """)
    int markReturned(@Param("id") Long id);

    /** 待上传：已采集或上传失败且未超过重试上限 */
    @Select("""
            SELECT * FROM drug_trace_code
            WHERE status IN (1, 3) AND retry_count < #{maxRetry}
            ORDER BY id
            LIMIT #{limit}
            """)
    List<DrugTraceCode> selectToUpload(@Param("maxRetry") int maxRetry, @Param("limit") int limit);

    @Update("""
            UPDATE drug_trace_code SET status = 2, upload_time = #{at}, version = version + 1, update_time = NOW()
            WHERE id = #{id} AND status IN (1, 3)
            """)
    int markUploaded(@Param("id") Long id, @Param("at") LocalDateTime at);

    @Update("""
            UPDATE drug_trace_code SET status = 3, retry_count = retry_count + 1, version = version + 1,
                   update_time = NOW()
            WHERE id = #{id} AND status IN (1, 3)
            """)
    int markUploadFailed(@Param("id") Long id);
}
