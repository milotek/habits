{
  config,
  lib,
  ...
}: let
  cfg = config.services.habits;
in {
  options.services.habits = {
    enable = lib.mkEnableOption "the habits tracker";

    package = lib.mkOption {
      type = lib.types.package;
      description = "The habits package to run.";
    };

    port = lib.mkOption {
      type = lib.types.port;
      default = 8095;
      description = "Port the tracker listens on.";
    };

    openFirewall = lib.mkOption {
      type = lib.types.bool;
      default = false;
      description = "Whether to open {option}`services.habits.port` in the firewall.";
    };
  };

  config = lib.mkIf cfg.enable {
    systemd.services.habits = {
      description = "habits tracker";
      wantedBy = ["multi-user.target"];
      after = ["network.target"];

      environment = {
        HABITS_PORT = toString cfg.port;
        HABITS_DB = "/var/lib/habits/habits.db";
      };

      serviceConfig = {
        ExecStart = lib.getExe cfg.package;
        WorkingDirectory = "/var/lib/habits";
        StateDirectory = "habits";
        User = "habits";
        Group = "habits";
        Restart = "on-failure";

        CapabilityBoundingSet = [""];
        LockPersonality = true;
        MemoryDenyWriteExecute = false; # the JVM JITs, so W^X cannot be enforced
        NoNewPrivileges = true;
        PrivateDevices = true;
        PrivateTmp = true;
        ProcSubset = "pid";
        ProtectClock = true;
        ProtectControlGroups = true;
        ProtectHome = true;
        ProtectHostname = true;
        ProtectKernelLogs = true;
        ProtectKernelModules = true;
        ProtectKernelTunables = true;
        ProtectProc = "invisible";
        ProtectSystem = "strict";
        RestrictAddressFamilies = ["AF_INET" "AF_INET6" "AF_UNIX"];
        RestrictNamespaces = true;
        RestrictRealtime = true;
        RestrictSUIDSGID = true;
        SystemCallArchitectures = "native";
        SystemCallFilter = ["@system-service" "~@privileged" "~@resources"];
      };
    };

    users.users.habits = {
      isSystemUser = true;
      group = "habits";
    };
    users.groups.habits = {};

    networking.firewall.allowedTCPPorts = lib.mkIf cfg.openFirewall [cfg.port];
  };
}
