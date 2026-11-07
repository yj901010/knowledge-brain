# Local image build source transport

Build-LocalSpaceImage.py preserves the existing Java 25 Maven builder Pod, scoped
artifact Service/NetworkPolicy and per-node containerd image import workflow.
The source archive now uses bounded 700,000-character base64 chunks, each stored
in a separate ConfigMap and mounted through one read-only projected source volume.
The builder concatenates numbered chunks in order, decodes and extracts with bash
pipefail enabled. Each source ConfigMap is owned by the exact builder Pod and
recorded in .local/{service}-image.json for cleanup. Removing that Pod garbage-
collects its source chunks; explicit cleanup can also use the recorded names.

This fixes an actual single ConfigMap 1 MiB limit failure observed while adding
the personal Concept editor static bundle. Application service/database/event
boundaries, image hashing, Kubernetes runtime and artifact access policy stay the
same. No new infrastructure dependency, runtime data transfer, or credentials
are introduced. Actual multi-chunk build/import/deploy validation is recorded in
personal-concept-editor-validation.txt for this delivery.
