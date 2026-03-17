package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.DocumentVector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface DocumentVectorRepository extends JpaRepository<DocumentVector, Long> {
    List<DocumentVector> findByFileMd5(String fileMd5); // 查询某文件的所有分块

    /** 查询某文件的所有子切片（parentChunkId != null → 这些是用于 ES 检索的子切片） */
    List<DocumentVector> findByFileMd5AndParentChunkIdIsNotNull(String fileMd5);

    /** 查询某文件的所有父切片（parentChunkId == null） */
    List<DocumentVector> findByFileMd5AndParentChunkIdIsNull(String fileMd5);

    /** 通过文件指纹 + chunkId 精确查找单个切片（用于父切片回捞）*/
    java.util.Optional<DocumentVector> findByFileMd5AndChunkId(String fileMd5, Integer chunkId);
    
    /**
     * 删除指定文件MD5的所有文档向量记录
     * 
     * @param fileMd5 文件MD5
     */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM document_vectors WHERE file_md5 = ?1", nativeQuery = true)
    void deleteByFileMd5(String fileMd5);
}
