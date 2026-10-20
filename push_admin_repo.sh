#!/usr/bin/env bash
set -e

# Load from .env if present
if [ -f ".env" ]; then
  export $(grep -v '^#' .env | grep -E 'GITHUB_PAT|GITHUB_REPO_URL' | xargs) 2>/dev/null || true
fi

# Also check admin-pwa/.env if present
if [ -f "admin-pwa/.env" ]; then
  export $(grep -v '^#' admin-pwa/.env | grep -E 'GITHUB_PAT|GITHUB_REPO_URL' | xargs) 2>/dev/null || true
fi

PAT="${GITHUB_PAT}"
REPO="${GITHUB_REPO_URL:-https://github.com/lihonemajiwa/cyclecare-admin.git}"

if [ -z "$PAT" ] || [ "$PAT" = "your_github_personal_access_token_here" ]; then
  echo "❌ Error: GITHUB_PAT is not set."
  echo "Please open the Secrets panel in AI Studio or your .env file and set GITHUB_PAT to your GitHub Personal Access Token."
  exit 1
fi

# Clean repository URL without protocol
CLEAN_REPO=$(echo "$REPO" | sed -E 's#^https?://([^@]+@)?##' | sed -E 's#^git@github.com:#github.com/#')
AUTH_REMOTE="https://${PAT}@${CLEAN_REPO}"

echo "Configuring remote origin in admin-pwa..."
cd admin-pwa

git config user.name "Lihone Majiwa"
git config user.email "lihonemajiwa@gmail.com"

git remote remove origin 2>/dev/null || true
git remote add origin "$AUTH_REMOTE"

echo "Pushing 'main' branch to https://${CLEAN_REPO}..."
git branch -M main
git push -u origin main

# Reset remote to clean URL without PAT to avoid persisting token in git config
git remote set-url origin "https://${CLEAN_REPO}"

echo "✓ Successfully pushed admin-pwa to https://${CLEAN_REPO}!"
