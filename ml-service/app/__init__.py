"""StegoHX Analyzer Service.

A FastAPI microservice that performs statistical steganalysis on uploaded
media files and returns a structured threat assessment. It is consumed by
the StegoHX Java engine (Spring Boot) via REST, and can also be used
stand-alone for research and blue-team triage workflows.

Contract (see docs/API.md for the full specification):
    GET  /health                 -> liveness + version + model status
    GET  /api/v1/analyzers       -> registry of available analyzers
    POST /api/v1/analyze         -> full analysis of an uploaded file
"""

__version__ = "1.0.0"
