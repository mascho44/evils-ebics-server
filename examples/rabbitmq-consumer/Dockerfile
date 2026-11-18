FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY consumer.py .
RUN useradd --uid 10001 consumer && mkdir /data && chown consumer /data
USER consumer
ENV INBOX_PATH=/data/inbox.sqlite HEALTH_HOST=0.0.0.0
CMD ["python", "consumer.py"]
