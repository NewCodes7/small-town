package com.newcodes7.small_town.embedding.entity;

import org.hibernate.annotations.Type;

import com.newcodes7.small_town.global.config.HalfVectorType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "clova_chunk_vectors")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ClovaChunkVector {

    @Id
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "id")
    private ClovaArticleChunk chunk;

    @Type(HalfVectorType.class)
    @Column(name = "embedding_normalized", columnDefinition = "halfvec(1024)")
    private float[] embeddingNormalized;
}
