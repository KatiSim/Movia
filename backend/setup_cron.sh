#!/data/data/com.termux/files/usr/bin/bash
# Movia Automated Cron Setup Script for Termux

CRON_JOB_DAILY="0 3 * * * cd /data/data/com.termux/files/home/projects/media-parser && python3 content_filler.py --limit 100 >> logs/content_filler.log 2>&1"
CRON_JOB_WEEKLY_ALL="0 4 * * 0 cd /data/data/com.termux/files/home/projects/media-parser && python3 content_filler.py --all --resume >> logs/content_filler.log 2>&1"
CRON_JOB_WEEKLY_STATS="0 5 * * 0 cd /data/data/com.termux/files/home/projects/media-parser && python3 content_stats.py >> logs/content_stats.log 2>&1"

mkdir -p /data/data/com.termux/files/home/projects/media-parser/logs

echo "📋 Настройка расписания контент-конвейера Movia (crontab)..."

# Check if crond or crontab exists
if command -v crontab >/dev/null 2>&1; then
    (crontab -l 2>/dev/null | grep -v "content_filler.py" | grep -v "content_stats.py" | grep -v "^$"; echo "$CRON_JOB_DAILY"; echo "$CRON_JOB_WEEKLY_ALL"; echo "$CRON_JOB_WEEKLY_STATS") | crontab -
    echo "✅ Задачи cron успешно зарегистрированы:"
    crontab -l
else
    echo "⚠️ Утилита crontab не установлена в среде Termux. Файл расписания сохранен в config/crontab.sample"
    mkdir -p /data/data/com.termux/files/home/projects/media-parser/config
    cat <<EOF > /data/data/com.termux/files/home/projects/media-parser/config/crontab.sample
$CRON_JOB_DAILY
$CRON_JOB_WEEKLY_ALL
$CRON_JOB_WEEKLY_STATS
EOF
fi
