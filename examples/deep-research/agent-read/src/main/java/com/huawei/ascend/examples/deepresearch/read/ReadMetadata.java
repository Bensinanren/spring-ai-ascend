package com.huawei.ascend.examples.deepresearch.read;

/**
 * Metadata block of the read_url output contract (TOPOLOGY §3.3). {@code author}
 * and {@code publishDate} are nullable (many pricing pages expose neither);
 * {@code docType} is always set.
 */
public record ReadMetadata(String author, String publishDate, DocType docType) {
}
