package httpapi

import (
	"database/sql"
	"encoding/json"
	"net/http"
)

func (a API) handleUserPackages(w http.ResponseWriter, r *http.Request, userID int64) {
	switch r.Method {
	case http.MethodGet:
		rows, err := a.Users.DB.QueryContext(r.Context(), `
SELECT p.id, p.name, p.price, p.created_at
FROM user_packages up
JOIN packages p ON p.id = up.package_id
WHERE up.user_id = ?
ORDER BY p.id ASC
`, userID)
		if err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		defer rows.Close()

		items := make([]map[string]any, 0)
		for rows.Next() {
			var id int64
			var price int64
			var name, createdAt string
			if err := rows.Scan(&id, &name, &price, &createdAt); err != nil {
				writeError(w, http.StatusInternalServerError, err)
				return
			}
			items = append(items, map[string]any{"id": id, "name": name, "price": price, "createdAt": createdAt})
		}
		if err := rows.Err(); err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		writeJSON(w, http.StatusOK, items)

	case http.MethodPut:
		var req SetUserPackagesRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, err)
			return
		}
		if err := a.Users.SetUserPackages(r.Context(), userID, req.PackageIDs); err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		// return the current packages for convenience
		rows, err := a.Users.DB.QueryContext(r.Context(), `
SELECT p.id, p.name, p.price, p.created_at
FROM user_packages up
JOIN packages p ON p.id = up.package_id
WHERE up.user_id = ?
ORDER BY p.id ASC
`, userID)
		if err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		defer rows.Close()

		items := make([]map[string]any, 0)
		for rows.Next() {
			var id int64
			var price int64
			var name, createdAt string
			if err := rows.Scan(&id, &name, &price, &createdAt); err != nil {
				writeError(w, http.StatusInternalServerError, err)
				return
			}
			items = append(items, map[string]any{"id": id, "name": name, "price": price, "createdAt": createdAt})
		}
		if err := rows.Err(); err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		writeJSON(w, http.StatusOK, items)

	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func (a API) userHasPackages(r *http.Request, userID int64) (bool, error) {
	var n int
	err := a.Users.DB.QueryRowContext(r.Context(), `SELECT COUNT(1) FROM user_packages WHERE user_id = ?`, userID).Scan(&n)
	if err != nil {
		if err == sql.ErrNoRows {
			return false, nil
		}
		return false, err
	}
	return n > 0, nil
}
