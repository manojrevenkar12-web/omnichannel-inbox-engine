# The LangGraph agent: one HTTP endpoint the core calls per customer turn.
FROM python:3.13-slim
RUN useradd --uid 10001 --create-home agent
WORKDIR /app
COPY pyproject.toml ./
COPY inbox_agent ./inbox_agent
COPY knowledge ./knowledge
RUN pip install --no-cache-dir .
USER agent
EXPOSE 8090
CMD ["inbox-agent", "serve", "--host", "0.0.0.0", "--port", "8090"]
