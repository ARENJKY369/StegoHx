.PHONY: help analyzer engine console selftest build clean

help:
	@echo "StegoHX - local steganography operations & steganalysis suite"
	@echo ""
	@echo "  make analyzer   install + start the Python analyzer service (:8000)"
	@echo "  make engine     build + start the Spring Boot engine (:8080, needs JDK 17)"
	@echo "  make console    install + start the Next.js console (:3000)"
	@echo "  make selftest   verify analyzer separation on synthetic media"
	@echo "  make build      production-build the console"

analyzer:
	cd ml-service && (test -d .venv || python3 -m venv .venv) && \
		.venv/bin/pip -q install -r requirements.txt && \
		.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000

engine:
	cd backend && mvn spring-boot:run

console:
	cd frontend && (test -d node_modules || npm install) && npm run dev

selftest:
	cd ml-service && (test -d .venv || python3 -m venv .venv) && \
		.venv/bin/pip -q install -r requirements.txt && \
		.venv/bin/python scripts/selftest.py

build:
	cd frontend && (test -d node_modules || npm install) && npm run build

clean:
	cd frontend && rm -rf .next
	cd ml-service && rm -rf .venv
	cd backend && mvn clean -q || true
