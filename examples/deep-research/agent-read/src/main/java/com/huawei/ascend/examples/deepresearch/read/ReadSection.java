package com.huawei.ascend.examples.deepresearch.read;

/**
 * One heading-delimited slice of the extracted article. {@code heading} may be
 * empty for the leading un-headed block. Mirrors the {@code sections[]} entry
 * shape in the TOPOLOGY §3.3 output contract.
 */
public record ReadSection(String heading, String body) {
}
