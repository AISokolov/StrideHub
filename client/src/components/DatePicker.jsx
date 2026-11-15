import { formatDate, localDate } from "../utils/formatters";

export default function DatePicker({ value, onChange }) {
    const today = localDate();

    function selectDate(date) {
        if (/^\d{4}-\d{2}-\d{2}$/.test(date) && date <= localDate()) onChange(date);
    }

    function moveDay(offset) {
        const date = new Date(`${value}T12:00:00`);
        date.setDate(date.getDate() + offset);
        selectDate(localDate(date));
    }

    return (
        <div className="date-picker">
            <button type="button" aria-label="Previous day" onClick={() => moveDay(-1)}>&larr;</button>
            <label>
                <span className="selected-date" aria-hidden="true">{formatDate(`${value}T12:00:00`)}</span>
                <input type="date" aria-label={`Activity date: ${formatDate(`${value}T12:00:00`)}`} value={value} max={today} onClick={(event) => event.currentTarget.showPicker?.()} onChange={(event) => selectDate(event.target.value)} />
            </label>
            <button type="button" aria-label="Next day" disabled={value >= today} onClick={() => moveDay(1)}>&rarr;</button>
            <button type="button" onClick={() => onChange(localDate())}>Today</button>
        </div>
    );
}
