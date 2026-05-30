workspace "Spring AI Ascend Spike" "Wave 0 spike — agent-bus slice" {
    # Flat (default) identifier scope: top-level identifiers are globally unique
    # so relationships at model-block level can reference nested containers
    # by short name.

    model {
        springAiAscend = softwareSystem "Spring AI Ascend" "Agent runtime platform" "SAA Module" {
            properties {
                "saa.id" "SYS-SPRING-AI-ASCEND"
                "saa.kind" "software_system"
                "saa.level" "L0"
                "saa.view" "logical"
                "saa.status" "shipped"
                "saa.owner" "architecture"
            }

            agentBus = container "agent-bus" "Bus & State Hub plane" "Java 21 / Maven" "SAA Module" {
                properties {
                    "saa.id" "MOD-AGENT-BUS"
                    "saa.kind" "module"
                    "saa.level" "L1"
                    "saa.view" "development"
                    "saa.status" "shipped"
                    "saa.owner" "agent-bus"
                }
            }
        }

        capIngress = element "Edge Ingress" "Capability" "Edge-plane ingress envelope routing" "SAA Capability" {
            properties {
                "saa.id" "CAP-EDGE-INGRESS"
                "saa.kind" "capability"
                "saa.level" "L1"
                "saa.view" "scenarios"
                "saa.status" "shipped"
                "saa.owner" "agent-bus"
                "saa.sourceAdr" "ADR-0089"
            }
        }

        fpIngressEnvelope = element "Ingress Envelope Routing" "FunctionPoint" "Route IngressEnvelope to compute_control via IngressGateway" "SAA FunctionPoint" {
            properties {
                "saa.id" "FP-INGRESS-ENVELOPE"
                "saa.kind" "function_point"
                "saa.level" "L1"
                "saa.view" "scenarios"
                "saa.status" "shipped"
                "saa.owner" "agent-bus"
                "saa.sourceAdr" "ADR-0089"
            }
        }

        spiIngress = element "agent-bus ingress SPI" "SPI" "com.huawei.ascend.bus.spi.ingress" "SAA SPI" {
            properties {
                "saa.id" "SPI-BUS-INGRESS"
                "saa.kind" "spi_package"
                "saa.level" "L1"
                "saa.view" "development"
                "saa.status" "shipped"
                "saa.owner" "agent-bus"
                "saa.sourceFile" "agent-bus/src/main/java/com/huawei/ascend/bus/spi/ingress"
            }
        }

        capIngress -> fpIngressEnvelope "contains function point" "SAA Relationship" {
            properties {
                "saa.rel" "contains"
            }
        }

        agentBus -> fpIngressEnvelope "implements ingress envelope routing" "SAA Relationship" {
            properties {
                "saa.rel" "implements"
            }
        }

        agentBus -> spiIngress "declares ingress SPI" "SAA Relationship" {
            properties {
                "saa.rel" "declares_spi"
            }
        }
    }

    views {
        systemContext springAiAscend "L0-SystemContext" {
            include *
            autoLayout lr
        }

        container springAiAscend "L1-Development" {
            include *
            autoLayout lr
        }
    }
}
