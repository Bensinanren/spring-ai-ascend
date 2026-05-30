workspace "Invalid Profile Test" "Negative fixture — missing required saa.*" {
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

        fpBroken = element "Broken Function Point" "FunctionPoint" "Missing all common saa.* + tag-specific" "SAA FunctionPoint" {
        }

        badRel = element "Bad Relationship Source" "Capability" "Has illegal saa.rel" "SAA Capability" {
            properties {
                "saa.id" "CAP-BADREL"
                "saa.kind" "capability"
                "saa.level" "L1"
                "saa.view" "scenarios"
                "saa.status" "draft"
                "saa.owner" "test"
                "saa.sourceAdr" "ADR-0147"
            }
        }

        badRel -> capBroken "test" "SAA Relationship" {
            properties {
                "saa.rel" "definitely-not-a-real-rel-type"
            }
        }
    }
}
