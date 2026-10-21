// Filters of the Stocktaking Variance script report.
frappe.query_reports["Stocktaking Variance"] = {
	filters: [
		{
			fieldname: "session",
			label: __("Stocktaking Session"),
			fieldtype: "Link",
			options: "Stocktaking Session",
			reqd: 1,
			default: frappe.route_options && frappe.route_options.session,
		},
		{
			fieldname: "only_variance",
			label: __("Only rows with a difference or pending review"),
			fieldtype: "Check",
			default: 1,
		},
		{
			fieldname: "status",
			label: __("Row Status"),
			fieldtype: "Select",
			options: ["", "Counted", "Recount Required", "Recounted", "Manager Review", "Approved", "Finalized"],
		},
		{ fieldname: "counter", label: __("Counter"), fieldtype: "Link", options: "User" },
		{ fieldname: "item_group", label: __("Item Group"), fieldtype: "Link", options: "Item Group" },
	],
	formatter(value, row, column, data, default_formatter) {
		value = default_formatter(value, row, column, data);
		if (data && (column.fieldname === "qty_difference" || column.fieldname === "value_difference")) {
			const number = flt(data[column.fieldname]);
			if (number < 0) value = `<span style="color:var(--red-600)">${value}</span>`;
			else if (number > 0) value = `<span style="color:var(--green-600)">${value}</span>`;
		}
		if (data && column.fieldname === "status") {
			const colors = { "Manager Review": "orange", Recounted: "orange", "Recount Required": "red", Approved: "green", Finalized: "green", Counted: "blue" };
			const color = colors[data.status];
			if (color) value = `<span class="indicator-pill ${color}">${frappe.utils.escape_html(data.status)}</span>`;
		}
		return value;
	},
};
