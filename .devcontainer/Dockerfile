# 기본 이미지 선택
FROM mcr.microsoft.com/devcontainers/base:ubuntu

# # OpenJDK 17 설치 및 JAVA_HOME 설정
RUN apt-get update && export DEBIAN_FRONTEND=noninteractive \
    && apt-get install -y openjdk-17-jdk \
    && rm -rf /var/lib/apt/lists/* \
    && echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64' >> /etc/bash.bashrc \
    && echo 'export PATH=$JAVA_HOME/bin:$PATH' >> /etc/bash.bashrc

# Chrome을 설치하기 위한 사전 요구 사항 및 키 추가
RUN apt-get update && export DEBIAN_FRONTEND=noninteractive \
    && apt-get install -y --no-install-recommends \
    wget \
    gnupg \
    && rm -rf /var/lib/apt/lists/*

# Google Chrome 안정cat /etc/os-release 버전 설치
RUN wget -q -O - https://dl-ssl.google.com/linux/linux_signing_key.pub | gpg --dearmor -o /usr/share/keyrings/google-chrome.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/google-chrome.gpg] http://dl.google.com/linux/chrome/deb/ stable main" | tee /etc/apt/sources.list.d/google-chrome.list \
    && apt-get update \
    && apt-get install -y google-chrome-stable \
    && rm -rf /var/lib/apt/lists/*

# ChromeDriver 설치 (선택 사항이지만 Selenium을 사용한다면 필수)
# 현재 시스템에 설치된 Chrome 버전에 맞는 ChromeDriver를 설치해야 합니다.
# 아래는 Chrome 버전을 자동으로 감지하여 ChromeDriver를 설치하는 스크립트 예시입니다.
# 실제 환경에서는 Selenium WebDriverManager 라이브러리를 사용하는 것이 더 편리할 수 있습니다.
# 여기서는 수동 설치 방법을 보여줍니다.

# chromedriver 다운로드 및 PATH에 추가 (예시: Chrome 125 버전에 맞춰)
# 실제 Chrome 버전을 확인하여 다운로드 URL을 맞춰야 합니다.
# 'google-chrome-stable --version' 명령으로 버전을 확인하고,
# https://googlechromelabs.github.io/chrome-for-testing/ 에서 해당 버전에 맞는 ChromeDriver를 찾으세요.
# 예를 들어, 현재 설치된 Chrome 버전이 125.x.x.x 라면,
# CHROME_DRIVER_VERSION은 Chrome 125용 ChromeDriver 버전 (예: 125.0.6422.141)을 사용해야 합니다.
# 또는 최신 안정 버전 (stable)의 ChromeDriver를 사용해도 됩니다.

# Chrome 버전을 자동으로 찾아 ChromeDriver를 설치하는 더 강력한 스크립트:
ENV CHROME_DRIVER_VERSION "stable"
RUN apt-get update && export DEBIAN_FRONTEND=noninteractive \
    && apt-get install -y unzip \
    && CHROME_VERSION=$(google-chrome-stable --version | grep -oP '\d+\.\d+\.\d+\.\d+') \
    && CHROME_MAJOR_VERSION=$(echo $CHROME_VERSION | cut -d'.' -f1) \
    && LATEST_CHROMEDRIVER_VERSION=$(wget -qO- "https://googlechromelabs.github.io/chrome-for-testing/last-known-good-versions-with-downloads.json" | grep -oP "${CHROME_MAJOR_VERSION}\.\d+\.\d+\.\d+" | head -1) \
    && wget -q https://edgedl.me.gvt1.com/edgedl/chrome/chrome-for-testing/$LATEST_CHROMEDRIVER_VERSION/linux64/chromedriver-linux64.zip -O /tmp/chromedriver.zip \
    && unzip /tmp/chromedriver.zip -d /tmp/chromedriver \
    && mv /tmp/chromedriver/chromedriver-linux64/chromedriver /usr/local/bin/chromedriver \
    && chmod +x /usr/local/bin/chromedriver \
    && rm -rf /tmp/chromedriver.zip /tmp/chromedriver \
    && rm -rf /var/lib/apt/lists/*

# ---------------------------------------------------------
# 1. Node.js 설치 (v18+ / 여기선 20)
# ---------------------------------------------------------
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get update \
    && apt-get install -y nodejs \
    && rm -rf /var/lib/apt/lists/*

# ---------------------------------------------------------
# 1.5. npm 글로벌 설치 경로를 사용자 홈으로 변경 (EACCES 방지)
#   - "vscode 유저" 기준으로 설정/설치가 되도록 USER 전환 후 처리
# ---------------------------------------------------------
RUN mkdir -p /home/vscode/.npm-global \
    && chown -R vscode:vscode /home/vscode/.npm-global

USER vscode

RUN npm config set prefix "/home/vscode/.npm-global"

ENV PATH="/home/vscode/.npm-global/bin:${PATH}"

# ---------------------------------------------------------
# 2. Claude Code CLI 설치
# ---------------------------------------------------------
RUN npm install -g @anthropic-ai/claude-code

# ---------------------------------------------------------
# 3. Codex 관련 도구 설치
# ---------------------------------------------------------
RUN npm install -g @openai/codex

# (선택 사항) VS Code 내 터미널에서 바로 사용할 수 있도록 설정
ENV SHELL=/bin/bash

RUN sudo apt update
RUN sudo apt install -y postgresql-client

# 기본 작업 디렉토리 설정
WORKDIR /workspace