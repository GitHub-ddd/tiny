--
--  Copyright (c) 1997-2013, www.tinygroup.org (luo_guo@icloud.com).
--
--  Licensed under the GPL, Version 3.0 (the "License");
--  you may not use this file except in compliance with the License.
--  You may obtain a copy of the License at
--
--       http://www.gnu.org/licenses/gpl.html
--
--  Unless required by applicable law or agreed to in writing, software
--  distributed under the License is distributed on an "AS IS" BASIS,
--  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
--  See the License for the specific language governing permissions and
--  limitations under the License.
--

select country,prod,year,s
from sales_view_ref
model
partition by (country)
dimension by (prod, year)
measures (sale s)
ignore nav
-- cell_reference_options
unique dimension
-- here starts model_rules_clause
rules upsert sequential order
(
s[prod='mouse pad', year=2001] = s['mouse pad', 1999] + s['mouse pad', 2000],
s['standard mouse', 2002] = s['standard mouse', 2001]
)
order by country, prod, year


