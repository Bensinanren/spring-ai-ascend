workspace "Spring AI Ascend Spike Invalid" "Wave 0 spike — negative fixture" {
    model {
        capBroken = element "Broken Capability" "Capability" "Missing saa.owner / saa.sourceAdr" "SAA Capability" {
            properties {
                "saa.id" "CAP-BROKEN"
                "saa.kind" "capability"
                "saa.level" "L1"
                "saa.view" "scenarios"
                "saa.status" "draft"
            }
        }
    }
}
