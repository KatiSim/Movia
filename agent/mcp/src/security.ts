import { MAX_COMMAND_CHARS } from "./config.js";

const blockedCommands: Array<{ pattern: RegExp; reason: string }> = [
  {
    pattern: /(^|\s)(reboot|poweroff|shutdown|halt)(\s|$)/i,
    reason: "команды выключения или перезагрузки запрещены"
  },
  {
    pattern: /(^|\s)mkfs(?:\.|\s|$)/i,
    reason: "форматирование файловых систем запрещено"
  },
  {
    pattern: /\bdd\b[\s\S]*\bof=\/dev\//i,
    reason: "прямая запись на блочное устройство запрещена"
  },
  {
    pattern:
      /\brm\s+(?:-[a-z]*r[a-z]*f[a-z]*|-[a-z]*f[a-z]*r[a-z]*)\s+(?:\/|~|\$HOME)(?:\s|$)/i,
    reason: "рекурсивное удаление корневого или домашнего каталога запрещено"
  },
  {
    pattern: /\bkill\s+-9\s+1(?:\s|$)/i,
    reason: "остановка PID 1 запрещена"
  }
];

export function validateCommand(command: string): void {
  if (!command.trim()) {
    throw new Error("Команда пуста");
  }

  if (command.length > MAX_COMMAND_CHARS) {
    throw new Error(
      `Команда длиннее ${MAX_COMMAND_CHARS.toLocaleString("ru-RU")} символов`
    );
  }

  for (const rule of blockedCommands) {
    if (rule.pattern.test(command)) {
      throw new Error(`Команда заблокирована: ${rule.reason}`);
    }
  }
}

export function shellQuote(value: string): string {
  return `'${value.replaceAll("'", "'\\''")}'`;
}
