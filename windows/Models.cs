namespace TermoDom;

public record Zone(string Id, string Name, double? Temperature, double? Water, double? Planned, bool? Pump);
public record HeatingStatus(string? ServerTime, string? SystemTime, double? Outside, double? Percentage, double? Resets,
    IReadOnlyList<Zone> Zones, double? BufferTop, double? BufferMiddle, double? BufferBottom, double? Sanitary,
    bool? SanitaryPump, double? Boiler, bool? BoilerPump, bool? Fan, string? SyncKat, string? SyncPrizemlje);
public record TemperatureField(string Name, double Value, double Min, double Max, double Step);
public record Schedule(string Id, IReadOnlyList<TemperatureField> Fields, IReadOnlyList<double?> Defaults,
    string Action, string Method, IReadOnlyDictionary<string, string> Hidden);
public record SettingField(string Name, string Label, double Value);
public record SettingsData(IReadOnlyList<SettingField> Fields, string Action, string Method);
public record ChartMeta(string Id, string Title, string Path, string Unit, int Hours = 24)
{
    public override string ToString() => Title;
}
public record ChartSeries(string Label, IReadOnlyList<double?> Values, string Color, bool Stepped, string SteppedMode = "before");
public record ChartData(ChartMeta Meta, IReadOnlyList<string> Labels, IReadOnlyList<ChartSeries> Series);
public class ControllerException(string message, bool requiresLogin = false, bool mutationUnconfirmed = false) : Exception(message)
{
    public bool RequiresLogin { get; } = requiresLogin;
    public bool MutationUnconfirmed { get; } = mutationUnconfirmed;
}
